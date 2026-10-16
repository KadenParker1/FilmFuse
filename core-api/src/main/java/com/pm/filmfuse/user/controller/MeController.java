package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.dto.UpdateProfileRequestDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import com.pm.filmfuse.user.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own profile. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final ProfileService profileService;

    public MeController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /** Auto-provisions the profile on the very first call. */
    @GetMapping
    public ProfileResponseDTO get(@CurrentUser Profile profile) {
        return profileService.get(profile);
    }

    /** Partial update; see {@link UpdateProfileRequestDTO} for null/blank semantics. 409 on a taken username. */
    @PatchMapping
    public ProfileResponseDTO update(@CurrentUser Profile profile,
                                     @Valid @RequestBody UpdateProfileRequestDTO request) {
        return profileService.update(profile, request);
    }
}
