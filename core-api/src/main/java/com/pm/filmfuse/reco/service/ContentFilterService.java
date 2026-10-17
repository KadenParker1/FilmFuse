package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
import com.pm.filmfuse.reco.model.CandidateCriteria;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.model.MovieContent;
import com.pm.filmfuse.reco.repository.MovieContentRepository;
import com.pm.filmfuse.user.service.ComfortSettingsService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Content comfort filtering, shared by group sessions and (Phase 1b) personal recommendations.
 *
 * <ul>
 *   <li>{@link #effective}: the filter a set of people gets - per field the most restrictive value
 *       across every member plus the group's cap; {@code allowUnrated} only if every member allows
 *       it (a cap never loosens it).</li>
 *   <li>{@link #eligiblePool}: the movies that pass it, for the ranker to choose from.</li>
 *   <li>{@link #contentOf} + {@link ContentFilter#isEligible}: re-checking whatever a ranker
 *       returns (defence in depth - a buggy ranker must never leak an R movie into a kids group).</li>
 * </ul>
 */
@Service
public class ContentFilterService {

    /** How many eligible candidates are handed to the ranker. */
    public static final int POOL_SIZE = 200;

    /** A pool smaller than this (twice the 20 results a session or page shows) triggers enrichment. */
    public static final int ENRICH_BELOW = 40;

    /** Movies looked up synchronously when a pool comes up short. */
    public static final int SYNC_ENRICH = 30;

    private final ComfortSettingsService comfortSettings;
    private final MovieContentRepository movies;
    private final CatalogEnrichmentService enrichment;

    public ContentFilterService(ComfortSettingsService comfortSettings, MovieContentRepository movies,
                                CatalogEnrichmentService enrichment) {
        this.comfortSettings = comfortSettings;
        this.movies = movies;
        this.enrichment = enrichment;
    }

    /** The effective filter of {@code memberIds} under {@code groupCap} (null = no cap). */
    @Transactional(readOnly = true)
    public ContentFilter effectiveFor(Collection<UUID> memberIds, ContentFilter groupCap) {
        Map<UUID, ContentFilter> comfort = comfortSettings.comfortOf(memberIds);
        return effective(comfort.values(), groupCap);
    }

    /**
     * Most restrictive across {@code members} and {@code groupCap} (null = no cap). With no members
     * at all the result is still conservative about unrated content ({@link ContentFilter#USER_DEFAULT}).
     */
    public static ContentFilter effective(Collection<ContentFilter> members, ContentFilter groupCap) {
        List<ContentFilter> all = new ArrayList<>(members.isEmpty() ? List.of(ContentFilter.USER_DEFAULT) : members);
        if (groupCap != null) {
            // A cap restricts levels only; it can never turn allowUnrated on.
            all.add(ContentFilter.cap(groupCap.maxCertification(), groupCap.contentLimits()));
        }
        return ContentFilter.mostRestrictive(all);
    }

    public boolean isEligible(MovieContent movie, ContentFilter filter) {
        return filter.isEligible(movie);
    }

    /**
     * Up to {@code limit} eligible movie ids, most popular first, excluding {@code excluded}.
     *
     * <p>When fewer than {@code min(limit, }{@value #ENRICH_BELOW}{@code )} pass, the movie cache is
     * topped up once and the pool rebuilt: a nearly empty cache first gets the catalog's popular
     * pages ({@link CatalogEnrichmentService#seedIfSparse}); then, if the filter has limits that
     * unrated movies fail, {@value #SYNC_ENRICH} never-checked movies get their certification and
     * keywords looked up ({@link CatalogEnrichmentService#enrich}) - list-cached movies have none,
     * so without this a PG-13 member could get an empty pool. Not transactional on purpose:
     * enrichment calls the catalog and commits its own writes.
     */
    public List<Integer> eligiblePool(ContentFilter filter, CandidateCriteria criteria, Collection<Integer> excluded,
                                      int limit) {
        List<Integer> pool = movies.eligiblePool(filter, criteria, excluded, limit);
        if (pool.size() >= Math.min(limit, ENRICH_BELOW)) {
            return pool;
        }
        boolean changed = enrichment.seedIfSparse();
        if (filter.hasLimits() && !filter.allowUnrated() && enrichment.hasUnchecked()) {
            changed |= enrichment.enrich(SYNC_ENRICH) > 0;
        }
        return changed ? movies.eligiblePool(filter, criteria, excluded, limit) : pool;
    }

    /** The cached content of {@code movieIds}; ids not in the movie cache are absent. */
    @Transactional(readOnly = true)
    public Map<Integer, MovieContent> contentOf(Collection<Integer> movieIds) {
        return movies.findContent(movieIds);
    }
}
