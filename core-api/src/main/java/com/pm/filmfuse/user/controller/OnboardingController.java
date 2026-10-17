package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.OnboardingStateDTO;
import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.dto.UpdateOnboardingStepDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import com.pm.filmfuse.user.service.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's onboarding progress. Each step's answers are saved through their own endpoints. */
@RestController
@RequestMapping("/api/v1/me/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /** Everything saved so far (resumable). */
    @GetMapping
    public OnboardingStateDTO state(@CurrentUser Profile profile) {
        return onboardingService.state(profile);
    }

    /** Records progress (0-8); a lower step than the stored one is ignored. */
    @PutMapping("/step")
    public OnboardingStateDTO updateStep(@CurrentUser Profile profile, @Valid @RequestBody UpdateOnboardingStepDTO request) {
        return onboardingService.updateStep(profile, request.step());
    }

    /**
     * Marks onboarding complete and builds the first taste vector. 400 until there is a username,
     * 5+ genre scores (3+ of them 8+) and 3+ favourites; idempotent once complete.
     */
    @PostMapping("/complete")
    public ProfileResponseDTO complete(@CurrentUser Profile profile) {
        return onboardingService.complete(profile);
    }
}
