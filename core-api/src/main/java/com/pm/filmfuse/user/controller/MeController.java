package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.dto.UpdateProfileRequestDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import com.pm.filmfuse.user.service.AccountDeletionService;
import com.pm.filmfuse.user.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own profile. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final ProfileService profileService;
    private final AccountDeletionService accountDeletionService;

    public MeController(ProfileService profileService, AccountDeletionService accountDeletionService) {
        this.profileService = profileService;
        this.accountDeletionService = accountDeletionService;
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

    /** Deletes the account: profile row, profile photo and the Supabase Auth user. 204. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser Profile profile) {
        accountDeletionService.delete(profile);
    }
}
