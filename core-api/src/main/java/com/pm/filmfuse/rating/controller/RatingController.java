package com.pm.filmfuse.rating.controller;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.rating.dto.CalibrationRatingDTO;
import com.pm.filmfuse.rating.dto.RatingDTO;
import com.pm.filmfuse.rating.dto.UpsertRatingDTO;
import com.pm.filmfuse.rating.service.RatingService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's ratings: quick rate / review ({@code /ratings/{movieId}}), their list, and calibration batches. */
@RestController
@RequestMapping("/api/v1")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    /** Create or overwrite. Unknown movie: fetched from the catalog, 404 when the catalog does not know it. */
    @PutMapping("/ratings/{movieId}")
    public RatingDTO upsert(@CurrentUser Profile caller, @PathVariable int movieId,
                            @Valid @RequestBody UpsertRatingDTO request) {
        return ratingService.upsert(caller.getId(), movieId, request);
    }

    /** 204, idempotent. */
    @DeleteMapping("/ratings/{movieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser Profile caller, @PathVariable int movieId) {
        ratingService.delete(caller.getId(), movieId);
    }

    /** Every source, newest first. */
    @GetMapping("/me/ratings")
    public PageDTO<RatingDTO> list(@CurrentUser Profile caller, @RequestParam(required = false) String cursor) {
        return ratingService.list(caller.getId(), cursor);
    }

    /** 404 when the caller has not rated the movie. */
    @GetMapping("/me/ratings/{movieId}")
    public RatingDTO get(@CurrentUser Profile caller, @PathVariable int movieId) {
        return ratingService.get(caller.getId(), movieId);
    }

    /** Calibration swipes (1-50): {@code [{movieId, score}]} or {@code [{movieId, unseen: true}]}. 204. */
    @PostMapping("/me/ratings/batch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void batch(@CurrentUser Profile caller, @RequestBody List<CalibrationRatingDTO> request) {
        ratingService.batch(caller.getId(), request);
    }
}
