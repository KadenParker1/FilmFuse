package com.pm.filmfuse.rating.service;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.pagination.CursorCodec;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.repository.MovieRepository;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.rating.dto.FeedItemDTO;
import com.pm.filmfuse.rating.dto.FeedScope;
import com.pm.filmfuse.rating.mapper.FeedMapper;
import com.pm.filmfuse.rating.model.FeedCursor;
import com.pm.filmfuse.rating.model.FeedEntry;
import com.pm.filmfuse.rating.model.FeedFilter;
import com.pm.filmfuse.rating.repository.FeedRepository;
import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.mapper.SocialMapper;
import com.pm.filmfuse.social.service.SocialGraphService;
import com.pm.filmfuse.social.service.SocialUsers;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ratings feed: other people's ratings in three lists (the feed, a movie's ratings, a user's
 * ratings) and likes. Who may see what is decided only by
 * {@link com.pm.filmfuse.rating.repository.RatingVisibilitySql}; this class picks the list and
 * pages it ({@value #PAGE_SIZE} per page, newest {@code updatedAt} first, so a re-rating moves up).
 */
@Service
public class FeedService {

    public static final int PAGE_SIZE = 20;

    static final String MOVIE_NOT_FOUND = "Movie not found.";
    static final String RATING_NOT_FOUND = "Rating not found.";
    static final String USER_NOT_FOUND = "User not found.";

    private final FeedRepository feed;
    private final MovieRepository movies;
    private final MovieSummaries movieSummaries;
    private final SocialGraphService graph;
    private final SocialUsers socialUsers;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public FeedService(FeedRepository feed, MovieRepository movies, MovieSummaries movieSummaries,
                       SocialGraphService graph, SocialUsers socialUsers, PlatformTransactionManager transactionManager,
                       Clock clock) {
        this.feed = feed;
        this.movies = movies;
        this.movieSummaries = movieSummaries;
        this.graph = graph;
        this.socialUsers = socialUsers;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    /** FRIENDS: the caller's and their friends' visible ratings. EVERYONE: visible PUBLIC ratings by anyone. */
    public PageDTO<FeedItemDTO> feed(UUID viewer, FeedScope scope, String cursor) {
        return page(viewer, FeedFilter.feed(scope), cursor);
    }

    /** {@link #feed} narrowed to one movie; 404 when the movie is not in the movie cache (it can have no ratings). */
    public PageDTO<FeedItemDTO> movieRatings(UUID viewer, int movieId, FeedScope scope, String cursor) {
        FeedCursor after = decode(cursor);
        return readTx.execute(status -> {
            if (!movies.existsById(movieId)) {
                throw new NotFoundException(MOVIE_NOT_FOUND);
            }
            return load(viewer, FeedFilter.movie(movieId, scope), after);
        });
    }

    /**
     * {@code userId}'s ratings that the caller may see (the caller's own: every non-swipe rating,
     * PRIVATE included). 404 for an unknown or suspended user, or a block either way.
     */
    public PageDTO<FeedItemDTO> userRatings(UUID viewer, UUID userId, String cursor) {
        FeedCursor after = decode(cursor);
        boolean self = viewer.equals(userId);
        return readTx.execute(status -> {
            if (!self) {
                socialUsers.requireActive(userId);
                if (graph.isBlockedEitherWay(viewer, userId)) {
                    throw new NotFoundException(USER_NOT_FOUND);
                }
            }
            return load(viewer, FeedFilter.author(userId, self), after);
        });
    }

    /** 204, idempotent; 404 when the rating does not exist or the caller may not see it. */
    public void like(UUID viewer, UUID ratingId) {
        tx.executeWithoutResult(status -> {
            if (!feed.like(viewer, ratingId, clock.instant()) && !feed.isVisible(viewer, ratingId)) {
                throw new NotFoundException(RATING_NOT_FOUND);
            }
        });
    }

    /** 204, idempotent - also for a rating that does not exist or is no longer visible. */
    public void unlike(UUID viewer, UUID ratingId) {
        tx.executeWithoutResult(status -> feed.unlike(viewer, ratingId));
    }

    private PageDTO<FeedItemDTO> page(UUID viewer, FeedFilter filter, String cursor) {
        FeedCursor after = decode(cursor);
        return readTx.execute(status -> load(viewer, filter, after));
    }

    private PageDTO<FeedItemDTO> load(UUID viewer, FeedFilter filter, FeedCursor after) {
        List<FeedEntry> rows = feed.page(viewer, filter, after, PAGE_SIZE + 1);
        boolean more = rows.size() > PAGE_SIZE;
        List<FeedEntry> page = more ? rows.subList(0, PAGE_SIZE) : rows;
        Map<Integer, MovieSummaryDTO> moviesById = movieSummaries.of(page.stream().map(FeedEntry::movieId).toList());
        Map<UUID, FriendshipState> states = graph.statesFor(viewer,
                page.stream().map(entry -> entry.author().id()).distinct().toList());
        List<FeedItemDTO> items = page.stream()
                .map(entry -> FeedMapper.toDTO(entry,
                        SocialMapper.toSummaryDTO(entry.author(), states.get(entry.author().id())),
                        moviesById.get(entry.movieId()), viewer))
                .toList();
        String next = more ? encode(page.getLast()) : null;
        return new PageDTO<>(items, next);
    }

    static String encode(FeedEntry last) {
        return CursorCodec.encode(List.of(last.updatedAt().toString(), last.id().toString()));
    }

    static FeedCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        List<String> parts = CursorCodec.decode(cursor, 2);
        try {
            return new FeedCursor(Instant.parse(parts.get(0)), UUID.fromString(parts.get(1)));
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            throw new BadRequestException(CursorCodec.INVALID_CURSOR);
        }
    }
}
