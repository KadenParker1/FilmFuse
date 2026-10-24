package com.pm.filmfuse.admin.settings;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.movie.seed.CatalogRefreshProperties;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The configured defaults of the catalog-job settings that are not a single property as such, and the
 * jobs an admin can pause.
 */
@Component
public class AdminSettingDefaults {

    /** TMDB popular-list pages hold 20 movies. */
    public static final int MOVIES_PER_PAGE = 20;

    /** The jobs with a runtime pause flag ({@code jobs.<name>.paused}) and a run-now entry point. */
    public static final List<String> PAUSABLE_JOBS = List.of(JobNames.CATALOG_TOP_UP, JobNames.CATALOG_REFRESH);

    private final CatalogSeedProperties seed;
    private final CatalogRefreshProperties refresh;

    public AdminSettingDefaults(CatalogSeedProperties seed, CatalogRefreshProperties refresh) {
        this.seed = seed;
        this.refresh = refresh;
    }

    /** The top-up's configured popular pages, in movies. */
    public Object topUpSize() {
        return seed.topUp().pages() * MOVIES_PER_PAGE;
    }

    public Object mainTierSize() {
        return refresh.mainTierSize();
    }

    public List<String> jobNames() {
        return PAUSABLE_JOBS;
    }

    /** Movies to popular pages, rounded up (1,000 movies = 50 pages). */
    public static int pagesFor(long movies) {
        return (int) Math.max(1, (movies + MOVIES_PER_PAGE - 1) / MOVIES_PER_PAGE);
    }
}
