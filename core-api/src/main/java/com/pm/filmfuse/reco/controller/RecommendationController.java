package com.pm.filmfuse.reco.controller;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.reco.dto.RecommendationDTO;
import com.pm.filmfuse.reco.service.CalibrationService;
import com.pm.filmfuse.reco.service.PersonalRecommender;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Personal "For you" and the calibration deck. Both apply the caller's comfort settings. */
@RestController
@RequestMapping("/api/v1/me")
public class RecommendationController {

    private final PersonalRecommender personalRecommender;
    private final CalibrationService calibrationService;

    public RecommendationController(PersonalRecommender personalRecommender, CalibrationService calibrationService) {
        this.personalRecommender = personalRecommender;
        this.calibrationService = calibrationService;
    }

    /** 20 per page, best first; never a rated, unseen or dismissed movie. */
    @GetMapping("/recommendations")
    public PageDTO<RecommendationDTO> recommendations(@CurrentUser Profile caller,
                                                      @RequestParam(required = false) String cursor) {
        return personalRecommender.recommendations(caller.getId(), cursor);
    }

    /** "Not interested". 204, idempotent; 404 for a movie the catalog does not know. */
    @PostMapping("/recommendations/{movieId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@CurrentUser Profile caller, @PathVariable int movieId) {
        personalRecommender.dismiss(caller.getId(), movieId);
    }

    /** The next calibration posters: never rated, unseen or favourite movies. */
    @GetMapping("/calibration/next")
    public List<MovieSummaryDTO> calibration(
            @CurrentUser Profile caller,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "Must be between 1 and 30")
            @Max(value = 30, message = "Must be between 1 and 30") int count) {
        return calibrationService.next(caller.getId(), count);
    }
}
