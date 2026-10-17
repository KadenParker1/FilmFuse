package com.pm.filmfuse.rating.service;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.ForbiddenException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.pagination.CursorCodec;
import com.pm.filmfuse.rating.dto.CommentDTO;
import com.pm.filmfuse.rating.dto.CreateCommentDTO;
import com.pm.filmfuse.rating.mapper.CommentMapper;
import com.pm.filmfuse.rating.model.CommentEntry;
import com.pm.filmfuse.rating.model.CommentRef;
import com.pm.filmfuse.rating.model.RatingRef;
import com.pm.filmfuse.rating.repository.CommentRepository;
import com.pm.filmfuse.rating.repository.FeedRepository;
import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.mapper.SocialMapper;
import com.pm.filmfuse.social.service.SocialGraphService;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Comments on ratings. A rating's comments can be read and written only by someone who can see
 * the rating (404 otherwise - the same answer as for a rating that does not exist); comments by
 * people blocked either way with the caller are hidden. Both rules come from
 * {@link com.pm.filmfuse.rating.repository.RatingVisibilitySql}. {@value #PAGE_SIZE} per page,
 * oldest first. A comment can be deleted by its author or by the rating's author.
 */
@Service
public class CommentService {

    public static final int PAGE_SIZE = 30;

    static final String RATING_NOT_FOUND = "Rating not found.";
    static final String COMMENT_NOT_FOUND = "Comment not found.";
    static final String NOT_YOURS = "You can only delete your own comments or comments on your ratings.";

    private final CommentRepository comments;
    private final FeedRepository feed;
    private final SocialGraphService graph;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public CommentService(CommentRepository comments, FeedRepository feed, SocialGraphService graph,
                          ApplicationEventPublisher events, PlatformTransactionManager transactionManager, Clock clock) {
        this.comments = comments;
        this.feed = feed;
        this.graph = graph;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    public PageDTO<CommentDTO> list(UUID viewer, UUID ratingId, String cursor) {
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
        UUID afterComment = afterId;
        return readTx.execute(status -> {
            if (!feed.isVisible(viewer, ratingId)) {
                throw new NotFoundException(RATING_NOT_FOUND);
            }
            List<CommentEntry> rows = comments.page(viewer, ratingId, after, afterComment, PAGE_SIZE + 1);
            boolean more = rows.size() > PAGE_SIZE;
            List<CommentEntry> page = more ? rows.subList(0, PAGE_SIZE) : rows;
            List<CommentDTO> items = toDTOs(viewer, page);
            String next = more
                    ? CursorCodec.encode(List.of(page.getLast().createdAt().toString(), page.getLast().id().toString()))
                    : null;
            return new PageDTO<>(items, next);
        });
    }

    /** 201 with the comment; 404 when the caller cannot see the rating. Emails the rating's author after commit. */
    public CommentDTO create(UUID viewer, UUID ratingId, CreateCommentDTO request) {
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            if (!comments.insertIfVisible(id, viewer, ratingId, request.body(), clock.instant())) {
                throw new NotFoundException(RATING_NOT_FOUND);
            }
            RatingRef rating = comments.findRating(ratingId).orElseThrow();
            if (!rating.authorId().equals(viewer)) {
                events.publishEvent(new RatingCommentedEvent(id, ratingId, rating.authorId(), viewer, rating.movieId()));
            }
            return toDTOs(viewer, List.of(comments.find(id).orElseThrow())).getFirst();
        });
    }

    /**
     * 204 for the comment's author or the rating's author; 403 for anyone else who can see the
     * comment; 404 when it does not exist or the caller cannot see it.
     */
    public void delete(UUID viewer, UUID commentId) {
        tx.executeWithoutResult(status -> {
            CommentRef comment = comments.findRef(commentId)
                    .orElseThrow(() -> new NotFoundException(COMMENT_NOT_FOUND));
            if (!comment.authorId().equals(viewer) && !comment.ratingAuthorId().equals(viewer)) {
                if (!comments.isVisible(viewer, commentId)) {
                    throw new NotFoundException(COMMENT_NOT_FOUND);
                }
                throw new ForbiddenException(NOT_YOURS);
            }
            comments.delete(commentId);
        });
    }

    private List<CommentDTO> toDTOs(UUID viewer, List<CommentEntry> page) {
        Map<UUID, FriendshipState> states = graph.statesFor(viewer,
                page.stream().map(entry -> entry.author().id()).distinct().toList());
        return page.stream()
                .map(entry -> CommentMapper.toDTO(entry,
                        SocialMapper.toSummaryDTO(entry.author(), states.get(entry.author().id())), viewer))
                .toList();
    }
}
