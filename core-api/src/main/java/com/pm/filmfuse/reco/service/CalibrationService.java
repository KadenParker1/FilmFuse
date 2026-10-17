package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.service.MovieService;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.reco.ml.MlCalibrationCandidatesRequest;
import com.pm.filmfuse.reco.ml.MlServiceClient;
import com.pm.filmfuse.reco.ml.MlServiceException;
import com.pm.filmfuse.reco.model.CandidateCriteria;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.model.MovieContent;
import com.pm.filmfuse.reco.model.TasteDimensions;
import com.pm.filmfuse.reco.repository.TasteSignalRepository;
import com.pm.filmfuse.user.service.ComfortSettingsService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The next posters for the calibration swipe deck (onboarding step 4 and "Recalibrate").
 *
 * <p>The ML service picks them ({@code POST /internal/calibration-candidates}, with every movie the
 * caller rated, marked unseen or chose as a favourite as {@code seenMovieIds}) when configured. Its
 * answer is re-checked - seen, uncached and over-comfort movies are dropped - and topped up from the
 * fallback when short. <strong>Fallback (placeholder, not the calibration algorithm):</strong> the
 * comfort-eligible, unseen cached pool, most popular first, taken round-robin across
 * {@link TasteDimensions#GENRE_IDS} (the most popular unpicked movie of each genre in turn), then in
 * pool order. A nearly empty movie cache is warmed with the catalog's popular pages first.
 */
@Service
public class CalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);

    public static final int MAX_COUNT = 30;
    static final int WARM_PAGES = 3;

    private final ContentFilterService contentFilter;
    private final ComfortSettingsService comfortSettings;
    private final TasteSignalRepository signals;
    private final MlServiceClient mlClient;
    private final MovieService movieService;
    private final MovieSummaries movieSummaries;

    public CalibrationService(ContentFilterService contentFilter, ComfortSettingsService comfortSettings,
                              TasteSignalRepository signals, MlServiceClient mlClient, MovieService movieService,
                              MovieSummaries movieSummaries) {
        this.contentFilter = contentFilter;
        this.comfortSettings = comfortSettings;
        this.signals = signals;
        this.mlClient = mlClient;
        this.movieService = movieService;
        this.movieSummaries = movieSummaries;
    }

    /** Up to {@code count} (1-{@value #MAX_COUNT}) movies, in deck order. */
    public List<MovieSummaryDTO> next(UUID userId, int count) {
        ContentFilter filter = ContentFilterService.effective(List.of(comfortSettings.comfortOf(userId)), null);
        Set<Integer> seen = signals.calibrationSeen(userId);

        LinkedHashSet<Integer> picked = new LinkedHashSet<>(fromMl(userId, seen, filter, count));
        if (picked.size() < count) {
            Set<Integer> skip = new HashSet<>(seen);
            skip.addAll(picked);
            picked.addAll(fallback(filter, skip, count - picked.size()));
        }
        Map<Integer, MovieSummaryDTO> movies = movieSummaries.of(picked);
        return picked.stream().filter(movies::containsKey).map(movies::get).limit(count).toList();
    }

    private List<Integer> fromMl(UUID userId, Set<Integer> seen, ContentFilter filter, int count) {
        if (!mlClient.isConfigured()) {
            return List.of();
        }
        List<Integer> ids;
        try {
            ids = mlClient.calibrationCandidates(new MlCalibrationCandidatesRequest(userId,
                    seen.stream().sorted().toList(), count));
            if (ids == null) {
                return List.of();
            }
        } catch (MlServiceException ex) {
            log.warn("ML service unavailable, using the calibration fallback: {}", ex.getMessage());
            return List.of();
        }
        Map<Integer, MovieContent> content = contentFilter.contentOf(ids.stream().distinct().toList());
        return ids.stream().distinct()
                .filter(id -> !seen.contains(id))
                .filter(id -> content.containsKey(id) && filter.isEligible(content.get(id)))
                .limit(count)
                .toList();
    }

    /** See the class comment. */
    List<Integer> fallback(ContentFilter filter, Set<Integer> skip, int count) {
        List<Integer> pool = contentFilter.eligiblePool(filter, CandidateCriteria.NONE, skip, ContentFilterService.POOL_SIZE);
        if (pool.size() < count) {
            warmCache();
            pool = contentFilter.eligiblePool(filter, CandidateCriteria.NONE, skip, ContentFilterService.POOL_SIZE);
        }
        Map<Integer, MovieContent> content = contentFilter.contentOf(pool);
        Map<Integer, Deque<Integer>> byGenre = new LinkedHashMap<>();
        TasteDimensions.GENRE_IDS.forEach(genre -> byGenre.put(genre, new ArrayDeque<>()));
        for (Integer id : pool) {
            MovieContent movie = content.get(id);
            if (movie != null) {
                movie.genreIds().stream().filter(byGenre::containsKey).forEach(genre -> byGenre.get(genre).add(id));
            }
        }
        LinkedHashSet<Integer> picked = new LinkedHashSet<>();
        boolean progress = true;
        while (picked.size() < count && progress) {
            progress = false;
            for (Deque<Integer> queue : byGenre.values()) {
                if (picked.size() == count) {
                    break;
                }
                while (!queue.isEmpty()) {
                    if (picked.add(queue.poll())) {
                        progress = true;
                        break;
                    }
                }
            }
        }
        for (Integer id : pool) {
            if (picked.size() == count) {
                break;
            }
            picked.add(id);
        }
        return new ArrayList<>(picked);
    }

    private void warmCache() {
        for (int page = 1; page <= WARM_PAGES; page++) {
            try {
                if (movieService.popular(null, String.valueOf(page)).nextCursor() == null) {
                    break;
                }
            } catch (RuntimeException ex) {
                log.warn("Could not warm the movie cache for calibration: {}", ex.getMessage());
                break;
            }
        }
    }
}
