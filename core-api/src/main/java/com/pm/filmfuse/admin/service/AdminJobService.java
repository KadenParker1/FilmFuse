package com.pm.filmfuse.admin.service;

import com.pm.filmfuse.admin.dto.AdminAction;
import com.pm.filmfuse.admin.dto.AdminJobDTO;
import com.pm.filmfuse.admin.dto.JobRunDTO;
import com.pm.filmfuse.admin.dto.JobRunStartedDTO;
import com.pm.filmfuse.admin.repository.AdminJobRunRepository;
import com.pm.filmfuse.admin.settings.AdminSettingDefaults;
import com.pm.filmfuse.admin.settings.AdminSettingsRegistry;
import com.pm.filmfuse.admin.settings.DbCatalogSettings;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.ConflictException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import com.pm.filmfuse.movie.seed.CatalogJobs;
import com.pm.filmfuse.movie.seed.CatalogRefreshSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background jobs for the admin portal: status from {@code app.job_runs} plus the schedule, and the
 * controls of the catalog freshness jobs - Run now (asynchronous, on one dedicated thread, through
 * {@link CatalogJobs#runNow}), Pause and Resume (the {@code jobs.<name>.paused} settings that
 * {@link DbCatalogSettings} hands the schedulers). Every control is audited.
 */
@Service
public class AdminJobService {

    private static final Logger log = LoggerFactory.getLogger(AdminJobService.class);

    static final int RECENT_RUNS = 5;
    static final String ALREADY_RUNNING = "ALREADY_RUNNING: a catalog job is already running. Try again when it has finished.";
    static final String UNKNOWN_JOB = "Job not found.";
    static final String NOT_CONTROLLABLE = "This job can't be run or paused from here.";

    /** How long Run now waits for the started run's row (it is written as soon as the job takes the lock). */
    private final Duration waitForRunRow;

    /** A job's schedule as configured: property names of its switch and cron or interval. */
    private record Definition(String name, String label, String enabledProperty, String cronProperty,
                              String intervalProperty, boolean controllable) {
    }

    private static final List<Definition> JOBS = List.of(
            new Definition(JobNames.CATALOG_TOP_UP, "Nightly catalog top-up", "filmfuse.catalog.seed.top-up.enabled",
                    null, null, true),
            new Definition(JobNames.CATALOG_REFRESH, "Weekly catalog refresh", "filmfuse.catalog.refresh.enabled",
                    null, null, true),
            new Definition(JobNames.CATALOG_ENRICHMENT, "Catalog enrichment", "filmfuse.catalog.enrichment.enabled",
                    null, "filmfuse.catalog.enrichment.interval", false),
            new Definition(JobNames.VECTOR_REFRESH, "Taste vector refresh (failures only)",
                    "filmfuse.vectors.refresh.enabled", null, "filmfuse.vectors.refresh.interval", false),
            new Definition(JobNames.CLOSENESS, "Friend closeness", "filmfuse.closeness.enabled",
                    "filmfuse.closeness.cron", null, false),
            new Definition(JobNames.WEEKLY_DIGEST, "Weekly digest email", "filmfuse.digest.enabled",
                    "filmfuse.digest.cron", null, false),
            new Definition(JobNames.CATALOG_SEED, "Full catalog seed (CLI)", null, null, null, false));

    private final AdminJobRunRepository runs;
    private final CatalogJobs catalogJobs;
    private final CatalogJobLock lock;
    private final CatalogRefreshSettings settings;
    private final DbCatalogSettings dbSettings;
    private final AdminSettingsService adminSettings;
    private final AdminAuditLog audit;
    private final Environment environment;
    private final ExecutorService executor;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AdminJobService(AdminJobRunRepository runs, CatalogJobs catalogJobs, CatalogJobLock lock,
                           CatalogRefreshSettings settings, DbCatalogSettings dbSettings,
                           AdminSettingsService adminSettings, AdminAuditLog audit, Environment environment,
                           @Qualifier("adminJobExecutor") ExecutorService executor, TransactionTemplate transactions,
                           Clock clock) {
        this.runs = runs;
        this.catalogJobs = catalogJobs;
        this.lock = lock;
        this.settings = settings;
        this.dbSettings = dbSettings;
        this.adminSettings = adminSettings;
        this.audit = audit;
        this.environment = environment;
        this.executor = executor;
        this.transactions = transactions;
        this.clock = clock;
        this.waitForRunRow = environment.getProperty("filmfuse.admin.run-now-wait", Duration.class,
                Duration.ofSeconds(3));
    }

    public List<AdminJobDTO> list() {
        Map<String, List<JobRunDTO>> recent = runs.recentByJob(RECENT_RUNS);
        CatalogRefreshSettings.Settings current = settings.current();
        boolean catalogLockHeld = lock.isHeld();
        Instant now = clock.instant();
        return JOBS.stream().map(job -> toDTO(job, recent.getOrDefault(job.name(), List.of()), current,
                catalogLockHeld, now)).toList();
    }

    public AdminJobDTO get(String name) {
        Definition job = definition(name);
        return toDTO(job, runs.recentByJob(RECENT_RUNS).getOrDefault(name, List.of()), settings.current(),
                lock.isHeld(), clock.instant());
    }

    /**
     * Starts a catalog job in the background and answers once its run is recorded (or after a short
     * wait). 409 ALREADY_RUNNING when a catalog job holds the cross-process lock, Run now is already busy
     * in this process, or the run was skipped because another job took the lock first.
     */
    public JobRunStartedDTO runNow(UUID adminId, String name) {
        Definition job = controllable(name);
        String triggeredBy = "admin:" + adminId;
        if (lock.isHeld()) {
            throw new ConflictException(ALREADY_RUNNING);
        }
        Instant requested = clock.instant().minusSeconds(1);
        try {
            executor.execute(() -> {
                try {
                    CatalogJobs.Result result = catalogJobs.runNow(job.name(), triggeredBy);
                    log.info("Admin run of {} by {} ended {}", job.name(), adminId, result.outcome());
                } catch (RuntimeException ex) {
                    log.error("Admin run of {} failed", job.name(), ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            throw new ConflictException(ALREADY_RUNNING);
        }
        Optional<JobRunDTO> run = awaitRun(job.name(), triggeredBy, requested);
        if (run.isPresent() && run.get().outcome() == JobOutcome.SKIPPED) {
            throw new ConflictException(ALREADY_RUNNING);
        }
        Long runId = run.map(JobRunDTO::id).orElse(null);
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("runId", runId);
        started.put("triggeredBy", triggeredBy);
        transactions.executeWithoutResult(status -> audit.record(adminId, AdminAction.JOB_RUN, job.name(), null, started));
        return new JobRunStartedDTO(job.name(), runId, triggeredBy);
    }

    public AdminJobDTO pause(UUID adminId, String name) {
        return setPaused(adminId, name, true);
    }

    public AdminJobDTO resume(UUID adminId, String name) {
        return setPaused(adminId, name, false);
    }

    private AdminJobDTO setPaused(UUID adminId, String name, boolean paused) {
        Definition job = controllable(name);
        boolean was = dbSettings.isPaused(job.name());
        transactions.executeWithoutResult(status -> {
            adminSettings.store(adminId, AdminSettingsRegistry.jobPausedKey(job.name()), paused);
            audit.record(adminId, paused ? AdminAction.JOB_PAUSE : AdminAction.JOB_RESUME, job.name(),
                    Map.of("paused", was), Map.of("paused", paused));
        });
        return get(job.name());
    }

    private Optional<JobRunDTO> awaitRun(String jobName, String triggeredBy, Instant since) {
        long deadline = System.nanoTime() + waitForRunRow.toNanos();
        do {
            Optional<JobRunDTO> run = runs.latestStartedBy(jobName, triggeredBy, since);
            if (run.isPresent()) {
                return run;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        } while (System.nanoTime() < deadline);
        return Optional.empty();
    }

    private Definition definition(String name) {
        return JOBS.stream().filter(job -> job.name().equals(name)).findFirst()
                .orElseThrow(() -> new NotFoundException(UNKNOWN_JOB));
    }

    private Definition controllable(String name) {
        Definition job = definition(name);
        if (!job.controllable() || !AdminSettingDefaults.PAUSABLE_JOBS.contains(job.name())) {
            throw new BadRequestException(NOT_CONTROLLABLE);
        }
        return job;
    }

    private AdminJobDTO toDTO(Definition job, List<JobRunDTO> recent, CatalogRefreshSettings.Settings current,
                              boolean catalogLockHeld, Instant now) {
        boolean scheduled = job.enabledProperty() != null
                && environment.getProperty(job.enabledProperty(), Boolean.class, true);
        boolean paused = job.controllable() && dbSettings.isPaused(job.name());
        String schedule = scheduleOf(job, current);
        Instant next = null;
        if (scheduled && !paused && schedule != null && job.intervalProperty() == null) {
            next = nextRun(schedule, now);
        }
        JobRunDTO last = recent.isEmpty() ? null : recent.getFirst();
        boolean running = last != null && last.outcome() == JobOutcome.RUNNING && last.finishedAt() == null
                && (!job.name().startsWith("catalog-") || catalogLockHeld);
        return new AdminJobDTO(job.name(), job.label(), scheduled, paused, job.controllable(), schedule, next, running,
                last, List.copyOf(recent));
    }

    private String scheduleOf(Definition job, CatalogRefreshSettings.Settings current) {
        if (JobNames.CATALOG_TOP_UP.equals(job.name())) {
            return current.topUpCron();
        }
        if (JobNames.CATALOG_REFRESH.equals(job.name())) {
            return current.refreshCron();
        }
        if (job.cronProperty() != null) {
            return environment.getProperty(job.cronProperty());
        }
        if (job.intervalProperty() != null) {
            String interval = environment.getProperty(job.intervalProperty());
            return interval == null ? null : "every " + interval;
        }
        return null;
    }

    public static Instant nextRun(String cron, Instant now) {
        try {
            var next = CronExpression.parse(cron).next(now.atZone(ZoneOffset.UTC));
            return next == null ? null : next.toInstant();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
