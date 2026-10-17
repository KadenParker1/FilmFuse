package com.pm.filmfuse.rating.controller;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.rating.dto.FeedItemDTO;
import com.pm.filmfuse.rating.dto.FeedScope;
import com.pm.filmfuse.rating.service.FeedService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ratings feed and likes. Every list is 20 per page, newest {@code updatedAt} first; visibility
 * follows {@code RatingVisibilitySql} (contract: the plan's Phase 2b section).
 */
@RestController
@RequestMapping("/api/v1")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    /** {@code scope} is required: FRIENDS (friends' and own ratings) or EVERYONE (public ratings). */
    @GetMapping("/feed")
    public PageDTO<FeedItemDTO> feed(@CurrentUser Profile caller, @RequestParam FeedScope scope,
                                     @RequestParam(required = false) String cursor) {
        return feedService.feed(caller.getId(), scope, cursor);
    }

    /** The feed for one movie; 404 for a movie that is not cached. */
    @GetMapping("/movies/{id}/ratings")
    public PageDTO<FeedItemDTO> movieRatings(@CurrentUser Profile caller, @PathVariable int id,
                                             @RequestParam FeedScope scope,
                                             @RequestParam(required = false) String cursor) {
        return feedService.movieRatings(caller.getId(), id, scope, cursor);
    }

    /** A user's ratings the caller may see (own: PRIVATE included); 404 when unknown, suspended or blocked either way. */
    @GetMapping("/users/{id}/ratings")
    public PageDTO<FeedItemDTO> userRatings(@CurrentUser Profile caller, @PathVariable UUID id,
                                            @RequestParam(required = false) String cursor) {
        return feedService.userRatings(caller.getId(), id, cursor);
    }

    /** One rating as a feed item (the rating-detail screen); 404 when the caller cannot see it. */
    @GetMapping("/ratings/{ratingId}")
    public FeedItemDTO rating(@CurrentUser Profile caller, @PathVariable UUID ratingId) {
        return feedService.rating(caller.getId(), ratingId);
    }

    /** 204, idempotent; 404 when the caller cannot see the rating. */
    @PostMapping("/ratings/{ratingId}/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void like(@CurrentUser Profile caller, @PathVariable UUID ratingId) {
        feedService.like(caller.getId(), ratingId);
    }

    /** 204, idempotent. */
    @DeleteMapping("/ratings/{ratingId}/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlike(@CurrentUser Profile caller, @PathVariable UUID ratingId) {
        feedService.unlike(caller.getId(), ratingId);
    }
}
