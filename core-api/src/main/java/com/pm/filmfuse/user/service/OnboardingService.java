package com.pm.filmfuse.user.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.mapper.ProfileMapper;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/**
 * Onboarding progress. Interim (until the Phase 1b taste steps exist): the only required step is
 * choosing a username, after which the client marks onboarding complete.
 */
@Service
public class OnboardingService {

    static final String USERNAME_REQUIRED = "Choose a username first.";

    /** The "profile basics" step; later steps (taste, calibration) will raise this. */
    static final int PROFILE_STEP = 1;

    private final ProfileRepository profileRepository;
    private final Clock clock;

    public OnboardingService(ProfileRepository profileRepository, Clock clock) {
        this.profileRepository = profileRepository;
        this.clock = clock;
    }

    /**
     * 400 without a username. Idempotent: a second call keeps the first completion timestamp, and
     * the step never goes backwards.
     */
    public ProfileResponseDTO complete(Profile profile) {
        if (profile.getUsername() == null) {
            throw new BadRequestException(USERNAME_REQUIRED);
        }
        boolean changed = false;
        if (profile.getOnboardingCompletedAt() == null) {
            // Postgres keeps microseconds; truncating keeps the response equal to what a re-read returns.
            profile.setOnboardingCompletedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
            changed = true;
        }
        if (profile.getOnboardingStep() < PROFILE_STEP) {
            profile.setOnboardingStep(PROFILE_STEP);
            changed = true;
        }
        return ProfileMapper.toDTO(changed ? profileRepository.saveAndFlush(profile) : profile);
    }
}
