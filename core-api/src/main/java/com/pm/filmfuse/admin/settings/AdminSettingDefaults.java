package com.pm.filmfuse.admin.settings;

import com.pm.filmfuse.movie.seed.CatalogSeedProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The configured defaults of the catalog-job settings that have no single property of their own yet,
 * and the names of the jobs an admin can pause.
 */
@Component
public class AdminSettingDefaults {

    /** TMDB list pages hold 20 movies. */
    static final int MOVIES_PER_PAGE = 20;

    private final CatalogSeedProperties seed;

    public AdminSettingDefaults(CatalogSeedProperties seed) {
        this.seed = seed;
    }

    public Object topUpSize() {
        return Math.max(100, Math.min(5_000, seed.topUp().pages() * MOVIES_PER_PAGE));
    }

    public Object mainTierSize() {
        return 20_000;
    }

    public List<String> jobNames() {
        return List.of("catalog-top-up");
    }
}
