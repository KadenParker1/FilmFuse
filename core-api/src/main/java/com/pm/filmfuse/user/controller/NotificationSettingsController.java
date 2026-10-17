package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.NotificationSettingsDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import com.pm.filmfuse.user.service.NotificationSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's email notification preferences. */
@RestController
@RequestMapping("/api/v1/me/settings/notifications")
public class NotificationSettingsController {

    private final NotificationSettingsService service;

    public NotificationSettingsController(NotificationSettingsService service) {
        this.service = service;
    }

    /** Defaults for a user who never saved any: everything on except the weekly digest. */
    @GetMapping
    public NotificationSettingsDTO get(@CurrentUser Profile caller) {
        return service.get(caller.getId());
    }

    /** Replaces all five preferences; a missing one is a 400. */
    @PutMapping
    public NotificationSettingsDTO update(@CurrentUser Profile caller, @Valid @RequestBody NotificationSettingsDTO request) {
        return service.update(caller.getId(), request);
    }
}
