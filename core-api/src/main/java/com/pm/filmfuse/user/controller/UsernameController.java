package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.UsernameAvailabilityDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import com.pm.filmfuse.user.service.ProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live username check for the onboarding / edit-profile form. */
@RestController
@RequestMapping("/api/v1/usernames")
public class UsernameController {

    private final ProfileService profileService;

    public UsernameController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/{username}/available")
    public UsernameAvailabilityDTO available(@CurrentUser Profile profile, @PathVariable String username) {
        return profileService.checkUsername(profile, username);
    }
}
