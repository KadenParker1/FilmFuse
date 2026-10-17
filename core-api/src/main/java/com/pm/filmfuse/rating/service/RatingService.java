package com.pm.filmfuse.rating.service;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.pagination.CursorCodec;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.service.MovieService;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.rating.dto.CalibrationRatingDTO;
import com.pm.filmfuse.rating.dto.RatingDTO;
import com.pm.filmfuse.rating.dto.RatingSource;
import com.pm.filmfuse.rating.dto.RatingVisibility;
import com.pm.filmfuse.rating.dto.UpsertRatingDTO;
import com.pm.filmfuse.rating.mapper.RatingMapper;
import com.pm.filmfuse.rating.model.Rating;
import com.pm.filmfuse.rating.repository.RatingRepository;
import com.pm.filmfuse.reco.dto.VectorChangeReason;
import com.pm.filmfuse.reco.service.VectorDirtyMarker;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ratings - the single source of truth for movie scores (quick rate, post-watch, calibration).
 * Every write marks the rater's vector dirty ({@code RATING}) in the same transaction. A movie not
 * yet in the movie cache is fetched from the catalog first (404 when the catalog does not know it).
 */
@Service
public class RatingService {

    public static final int PAGE_SIZE = 20;
    public static final int MAX_BATCH = 50;

    static final String RATING_NOT_FOUND = "You have not rated this movie.";
    static final String SWIPE_NOT_ALLOWED = "ONBOARDING_SWIPE ratings can only be sent through /me/ratings/batch.";
    static final String BATCH_SIZE = "Send between 1 and " + MAX_BATCH + " calibration ratings.";
    static final String BATCH_ITEM = "Each calibration rating needs a movieId and exactly one of a score (1-10) or unseen: true.";
    static final String BATCH_DUPLICATE = "Each movie can appear only once in a batch.";

    private final RatingRepository ratings;
    private final MovieService movieService;
    private final MovieSummaries movieSummaries;
    private final VectorDirtyMarker dirtyMarker;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public RatingService(RatingRepository ratings, MovieService movieService, MovieSummaries movieSummaries,
                         VectorDirtyMarker dirtyMarker, PlatformTransactionManager transactionManager, Clock clock) {
        this.ratings = ratings;
        this.movieService = movieService;
        this.movieSummaries = movieSummaries;
        this.dirtyMarker = dirtyMarker;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    /** Creates or overwrites the caller's rating; the movie is no longer "haven't seen". */
    public RatingDTO upsert(UUID userId, int movieId, UpsertRatingDTO request) {
        RatingSource source = request.source() == null ? RatingSource.QUICK_RATE : request.source();
        if (source == RatingSource.ONBOARDING_SWIPE) {
            throw new BadRequestException(SWIPE_NOT_ALLOWED);
        }
        RatingVisibility visibility = request.visibility() == null ? RatingVisibility.FRIENDS : request.visibility();
        String body = request.body() == null || request.body().isBlank() ? null : request.body().strip();
        movieService.ensureCached(List.of(movieId));
        Rating saved = tx.execute(status -> {
            Rating rating = ratings.upsert(userId, movieId, request.score(), body,
                    Boolean.TRUE.equals(request.containsSpoilers()), visibility, source, request.watchedOn(),
                    clock.instant());
            ratings.clearUnseen(userId, movieId);
            dirtyMarker.markDirty(userId, VectorChangeReason.RATING);
            return rating;
        });
        return toDTO(saved);
    }

    /** Idempotent. Marks the vector dirty only when a rating was actually removed. */
    public void delete(UUID userId, int movieId) {
        tx.executeWithoutResult(status -> {
            if (ratings.delete(userId, movieId)) {
                dirtyMarker.markDirty(userId, VectorChangeReason.RATING);
            }
        });
    }

    public RatingDTO get(UUID userId, int movieId) {
        return readTx.execute(status -> toDTO(ratings.find(userId, movieId)
                .orElseThrow(() -> new NotFoundException(RATING_NOT_FOUND))));
    }

    /** The caller's ratings of every source, newest first; {@value #PAGE_SIZE} per page. */
    public PageDTO<RatingDTO> list(UUID userId, String cursor) {
        Instant afterCreatedAt = null;
        UUID afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            List<String> parts = CursorCodec.decode(cursor, 2);
            try {
                afterCreatedAt = Instant.parse(parts.get(0));
                afterId = UUID.fromString(parts.get(1));
            } catch (DateTimeParseException | IllegalArgumentException ex) {
                throw new BadRequestException(CursorCodec.INVALID_CURSOR);
            }
        }
        Instant after = afterCreatedAt;
        UUID afterRating = afterId;
        return readTx.execute(status -> {
            List<Rating> rows = ratings.page(userId, after, afterRating, PAGE_SIZE + 1);
            boolean more = rows.size() > PAGE_SIZE;
            List<Rating> page = more ? rows.subList(0, PAGE_SIZE) : rows;
            Map<Integer, MovieSummaryDTO> movies = movieSummaries.of(page.stream().map(Rating::movieId).toList());
            List<RatingDTO> items = page.stream().map(r -> RatingMapper.toDTO(r, movies.get(r.movieId()))).toList();
            String next = more
                    ? CursorCodec.encode(List.of(page.getLast().createdAt().toString(), page.getLast().id().toString()))
                    : null;
            return new PageDTO<>(items, next);
        });
    }

    /**
     * Calibration swipes: scores become PRIVATE {@code ONBOARDING_SWIPE} ratings (an existing rating
     * only gets the new score), "haven't seen" goes to {@code rating_unseen}. All or nothing.
     */
    public void batch(UUID userId, List<CalibrationRatingDTO> request) {
        if (request == null || request.isEmpty() || request.size() > MAX_BATCH) {
            throw new BadRequestException(BATCH_SIZE);
        }
        Set<Integer> movieIds = new HashSet<>();
        for (CalibrationRatingDTO item : request) {
            boolean unseen = item != null && Boolean.TRUE.equals(item.unseen());
            if (item == null || item.movieId() == null || item.movieId() < 1
                    || (unseen == (item.score() != null))
                    || (item.score() != null && (item.score() < 1 || item.score() > 10))) {
                throw new BadRequestException(BATCH_ITEM);
            }
            if (!movieIds.add(item.movieId())) {
                throw new BadRequestException(BATCH_DUPLICATE);
            }
        }
        movieService.ensureCached(movieIds);
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            for (CalibrationRatingDTO item : request) {
                if (Boolean.TRUE.equals(item.unseen())) {
                    ratings.markUnseen(userId, item.movieId(), now);
                } else {
                    ratings.upsertCalibration(userId, item.movieId(), item.score(), now);
                    ratings.clearUnseen(userId, item.movieId());
                }
            }
            dirtyMarker.markDirty(userId, VectorChangeReason.RATING);
        });
    }

    public long calibrationRatedCount(UUID userId) {
        return ratings.countBySource(userId, RatingSource.ONBOARDING_SWIPE);
    }

    public long count(UUID userId) {
        return ratings.count(userId);
    }

    private RatingDTO toDTO(Rating rating) {
        return RatingMapper.toDTO(rating, movieSummaries.of(List.of(rating.movieId())).get(rating.movieId()));
    }
}
