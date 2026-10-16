package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.ConfirmProfilePhotoRequestDTO;
import com.pm.filmfuse.user.dto.ProfilePhotoUploadDTO;
import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import com.pm.filmfuse.user.service.ProfilePhotoService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's profile photo: get an upload URL, upload directly to storage, confirm.
 * This is the only way to change {@code profileImageUrl}.
 */
@RestController
@RequestMapping("/api/v1/me/profile-photo")
public class ProfilePhotoController {

    private final ProfilePhotoService profilePhotoService;

    public ProfilePhotoController(ProfilePhotoService profilePhotoService) {
        this.profilePhotoService = profilePhotoService;
    }

    @PostMapping("/upload-url")
    public ProfilePhotoUploadDTO uploadUrl(@CurrentUser Profile profile) {
        return profilePhotoService.createUploadUrl(profile);
    }

    /** 400 unless the path is {@code <callerId>/<uuid>.jpg}. The previous photo is deleted best-effort. */
    @PostMapping
    public ProfileResponseDTO confirm(@CurrentUser Profile profile,
                                      @Valid @RequestBody ConfirmProfilePhotoRequestDTO request) {
        return profilePhotoService.confirm(profile, request.path());
    }

    @DeleteMapping
    public ProfileResponseDTO remove(@CurrentUser Profile profile) {
        return profilePhotoService.remove(profile);
    }
}
