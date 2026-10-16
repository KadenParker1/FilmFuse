package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import com.pm.filmfuse.user.service.OnboardingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's onboarding progress. */
@RestController
@RequestMapping("/api/v1/me/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /** Marks onboarding complete. 400 until the profile has a username; idempotent afterwards. */
    @PostMapping("/complete")
    public ProfileResponseDTO complete(@CurrentUser Profile profile) {
        return onboardingService.complete(profile);
    }
}
