package com.pm.filmfuse.rating.controller;

import com.pm.filmfuse.rating.dto.PendingRatingDTO;
import com.pm.filmfuse.rating.service.PendingRatingService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Post-watch rating prompts (contract: Phase 5 section A). */
@RestController
@RequestMapping("/api/v1/me/pending-ratings")
public class PendingRatingController {

    private final PendingRatingService service;

    public PendingRatingController(PendingRatingService service) {
        this.service = service;
    }

    /** Session picks of the last 14 days not yet rated or dismissed; newest first, at most 10. */
    @GetMapping
    public List<PendingRatingDTO> list(@CurrentUser Profile caller) {
        return service.list(caller.getId());
    }

    /** 204, idempotent: that movie is never prompted again. 404 for an unknown movie. */
    @PostMapping("/{movieId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@CurrentUser Profile caller, @PathVariable int movieId) {
        service.dismiss(caller.getId(), movieId);
    }
}
