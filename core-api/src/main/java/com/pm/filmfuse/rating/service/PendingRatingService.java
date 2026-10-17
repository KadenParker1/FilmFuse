package com.pm.filmfuse.rating.service;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.repository.MovieRepository;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.rating.dto.PendingRatingDTO;
import com.pm.filmfuse.rating.model.PendingRating;
import com.pm.filmfuse.rating.repository.PendingRatingRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Post-watch prompts ("You watched Dune with Roommates - rate it?"): movies the caller's sessions
 * picked in the last {@value #WINDOW_DAYS} days that they have not rated by any route and not
 * dismissed, newest first, at most {@value #LIMIT}. Rating the movie ({@code PUT /ratings/{movieId}},
 * usually with {@code source: POST_WATCH}) removes the prompt; so does a dismissal, for good.
 */
@Service
public class PendingRatingService {

    public static final int WINDOW_DAYS = 14;
    public static final int LIMIT = 10;

    static final String MOVIE_NOT_FOUND = "Movie not found.";

    private final PendingRatingRepository repository;
    private final MovieRepository movies;
    private final MovieSummaries movieSummaries;
    private final Clock clock;

    public PendingRatingService(PendingRatingRepository repository, MovieRepository movies,
                                MovieSummaries movieSummaries, Clock clock) {
        this.repository = repository;
        this.movies = movies;
        this.movieSummaries = movieSummaries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PendingRatingDTO> list(UUID userId) {
        List<PendingRating> rows = repository.pending(userId, clock.instant().minus(Duration.ofDays(WINDOW_DAYS)),
                LIMIT);
        Map<Integer, MovieSummaryDTO> summaries = movieSummaries.of(rows.stream().map(PendingRating::movieId).toList());
        return rows.stream()
                .filter(row -> summaries.containsKey(row.movieId()))
                .map(row -> new PendingRatingDTO(summaries.get(row.movieId()), row.sessionId(), row.groupName(),
                        row.pickedAt()))
                .toList();
    }

    /** 204, idempotent; 404 for a movie that is not cached (it cannot be in anyone's watch history). */
    @Transactional
    public void dismiss(UUID userId, int movieId) {
        if (!movies.existsById(movieId)) {
            throw new NotFoundException(MOVIE_NOT_FOUND);
        }
        repository.dismiss(userId, movieId, clock.instant());
    }
}
