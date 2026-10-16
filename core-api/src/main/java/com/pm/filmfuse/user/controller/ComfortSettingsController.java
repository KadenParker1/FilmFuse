package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.ComfortSettingsDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import com.pm.filmfuse.user.service.ComfortSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's content comfort settings (hard filters on every recommendation they are part of). */
@RestController
@RequestMapping("/api/v1/me/settings/comfort")
public class ComfortSettingsController {

    private final ComfortSettingsService comfortSettingsService;

    public ComfortSettingsController(ComfortSettingsService comfortSettingsService) {
        this.comfortSettingsService = comfortSettingsService;
    }

    /** Defaults for a user who never saved any: no limits, unrated content not allowed. */
    @GetMapping
    public ComfortSettingsDTO get(@CurrentUser Profile caller) {
        return comfortSettingsService.get(caller.getId());
    }

    /** Replaces all comfort settings. An unknown certification, dimension or level is a 400. */
    @PutMapping
    public ComfortSettingsDTO update(@CurrentUser Profile caller, @RequestBody ComfortSettingsDTO request) {
        return comfortSettingsService.update(caller.getId(), request);
    }
}
