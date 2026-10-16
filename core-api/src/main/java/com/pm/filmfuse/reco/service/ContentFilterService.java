package com.pm.filmfuse.reco.service;

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

    private final ComfortSettingsService comfortSettings;
    private final MovieContentRepository movies;

    public ContentFilterService(ComfortSettingsService comfortSettings, MovieContentRepository movies) {
        this.comfortSettings = comfortSettings;
        this.movies = movies;
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

    /** Up to {@code limit} eligible movie ids, most popular first, excluding {@code excluded}. */
    @Transactional(readOnly = true)
    public List<Integer> eligiblePool(ContentFilter filter, CandidateCriteria criteria, Collection<Integer> excluded,
                                      int limit) {
        return movies.eligiblePool(filter, criteria, excluded, limit);
    }

    /** The cached content of {@code movieIds}; ids not in the movie cache are absent. */
    @Transactional(readOnly = true)
    public Map<Integer, MovieContent> contentOf(Collection<Integer> movieIds) {
        return movies.findContent(movieIds);
    }
}
