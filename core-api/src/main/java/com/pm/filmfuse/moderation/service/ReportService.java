package com.pm.filmfuse.moderation.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.moderation.dto.CreateReportDTO;
import com.pm.filmfuse.moderation.repository.ReportRepository;
import com.pm.filmfuse.rating.model.CommentRef;
import com.pm.filmfuse.rating.model.RatingRef;
import com.pm.filmfuse.rating.repository.CommentRepository;
import com.pm.filmfuse.rating.repository.FeedRepository;
import com.pm.filmfuse.social.service.SocialUsers;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports of ratings, comments and users (app-store UGC rules). The target must exist and - for a
 * rating or comment - be visible to the reporter under
 * {@link com.pm.filmfuse.rating.repository.RatingVisibilitySql} (404 otherwise, the same answer as
 * for an unknown id). Reporting yourself or your own content is a 400. One OPEN report per
 * reporter and target: a repeat is an idempotent 204 that stores nothing. Reports are logged at
 * INFO with ids only; there is no admin UI yet.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    static final String NOT_FOUND = "We couldn't find what you're reporting.";
    static final String SELF_REPORT = "You can't report yourself or your own posts.";

    private final ReportRepository reports;
    private final FeedRepository feed;
    private final CommentRepository comments;
    private final SocialUsers users;
    private final Clock clock;

    public ReportService(ReportRepository reports, FeedRepository feed, CommentRepository comments, SocialUsers users,
                         Clock clock) {
        this.reports = reports;
        this.feed = feed;
        this.comments = comments;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public void report(UUID reporterId, CreateReportDTO request) {
        UUID targetId = UUID.fromString(request.targetId());
        UUID owner = switch (request.targetType()) {
            case RATING -> {
                if (!feed.isVisible(reporterId, targetId)) {
                    throw new NotFoundException(NOT_FOUND);
                }
                yield comments.findRating(targetId).map(RatingRef::authorId).orElseThrow();
            }
            case COMMENT -> {
                if (!comments.isVisible(reporterId, targetId)) {
                    throw new NotFoundException(NOT_FOUND);
                }
                yield comments.findRef(targetId).map(CommentRef::authorId).orElseThrow();
            }
            case USER -> {
                try {
                    yield users.requireExists(targetId).user().id();
                } catch (NotFoundException ex) {
                    throw new NotFoundException(NOT_FOUND);
                }
            }
        };
        if (owner.equals(reporterId)) {
            throw new BadRequestException(SELF_REPORT);
        }
        UUID id = UUID.randomUUID();
        if (reports.insertIfNoOpen(id, reporterId, request.targetType(), targetId, request.reason(),
                request.details(), clock.instant())) {
            log.info("Report {} filed by {} on {} {} ({})", id, reporterId, request.targetType(), targetId,
                    request.reason());
        }
    }
}
