package com.pm.filmfuse.admin.controller;

import com.pm.filmfuse.admin.dto.AdminSettingDTO;
import com.pm.filmfuse.admin.dto.UpdateAdminSettingDTO;
import com.pm.filmfuse.admin.service.AdminSettingsService;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime settings (registry keys only; unknown key = 400). Every write is audited. Admin-only via SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class AdminSettingsController {

    private final AdminSettingsService settings;

    public AdminSettingsController(AdminSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public List<AdminSettingDTO> list() {
        return settings.list();
    }

    /** 400 for an unknown key, a value of the wrong type or out of bounds ({@code fieldErrors.value}). */
    @PutMapping("/{key}")
    public AdminSettingDTO update(@CurrentUser Profile admin, @PathVariable String key,
                                  @RequestBody UpdateAdminSettingDTO request) {
        if (request == null) {
            throw new BadRequestException("The request body could not be read as JSON.");
        }
        return settings.update(admin.getId(), key, request.value());
    }

    /** Back to the configured default. Idempotent. */
    @DeleteMapping("/{key}")
    public AdminSettingDTO reset(@CurrentUser Profile admin, @PathVariable String key) {
        return settings.reset(admin.getId(), key);
    }
}
