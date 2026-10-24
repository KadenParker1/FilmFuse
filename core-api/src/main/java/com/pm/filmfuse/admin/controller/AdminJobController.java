package com.pm.filmfuse.admin.controller;

import com.pm.filmfuse.admin.dto.AdminJobDTO;
import com.pm.filmfuse.admin.dto.JobRunStartedDTO;
import com.pm.filmfuse.admin.service.AdminJobService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Background jobs: status, and Run now / Pause / Resume for the catalog jobs. Admin-only via SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/jobs")
public class AdminJobController {

    private final AdminJobService jobs;

    public AdminJobController(AdminJobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    public List<AdminJobDTO> list() {
        return jobs.list();
    }

    /** 202 with the run id; 409 ALREADY_RUNNING; 400 for a job without controls; 404 unknown job. */
    @PostMapping("/{name}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRunStartedDTO run(@CurrentUser Profile admin, @PathVariable String name) {
        return jobs.runNow(admin.getId(), name);
    }

    /** Scheduled runs are skipped until resumed (Run now still works). Idempotent. */
    @PostMapping("/{name}/pause")
    public AdminJobDTO pause(@CurrentUser Profile admin, @PathVariable String name) {
        return jobs.pause(admin.getId(), name);
    }

    @PostMapping("/{name}/resume")
    public AdminJobDTO resume(@CurrentUser Profile admin, @PathVariable String name) {
        return jobs.resume(admin.getId(), name);
    }
}
