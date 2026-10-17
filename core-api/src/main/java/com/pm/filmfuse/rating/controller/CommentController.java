package com.pm.filmfuse.rating.controller;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.rating.dto.CommentDTO;
import com.pm.filmfuse.rating.dto.CreateCommentDTO;
import com.pm.filmfuse.rating.service.CommentService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Comments on ratings (contract: Phase 5 section C). Visibility follows {@code RatingVisibilitySql}. */
@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /** Oldest first, 30 per page; 404 when the caller cannot see the rating. */
    @GetMapping("/ratings/{ratingId}/comments")
    public PageDTO<CommentDTO> list(@CurrentUser Profile caller, @PathVariable UUID ratingId,
                                    @RequestParam(required = false) String cursor) {
        return commentService.list(caller.getId(), ratingId, cursor);
    }

    /** 201; 404 when the caller cannot see the rating; body 1-500 characters after trimming. */
    @PostMapping("/ratings/{ratingId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentDTO create(@CurrentUser Profile caller, @PathVariable UUID ratingId,
                             @Valid @RequestBody CreateCommentDTO request) {
        return commentService.create(caller.getId(), ratingId, request);
    }

    /** 204 for the comment's or the rating's author; 403 for others; 404 when unknown or hidden. */
    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser Profile caller, @PathVariable UUID commentId) {
        commentService.delete(caller.getId(), commentId);
    }
}
