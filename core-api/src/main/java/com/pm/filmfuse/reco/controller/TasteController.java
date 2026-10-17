package com.pm.filmfuse.reco.controller;

import com.pm.filmfuse.reco.dto.TasteHistoryEntryDTO;
import com.pm.filmfuse.reco.dto.TasteProfileDTO;
import com.pm.filmfuse.reco.service.TasteProfileService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's taste profile (stated vs learned) and how it changed. */
@RestController
@RequestMapping("/api/v1/me")
public class TasteController {

    private final TasteProfileService tasteProfileService;

    public TasteController(TasteProfileService tasteProfileService) {
        this.tasteProfileService = tasteProfileService;
    }

    @GetMapping("/taste-profile")
    public TasteProfileDTO profile(@CurrentUser Profile caller) {
        return tasteProfileService.profile(caller.getId());
    }

    /** Built vector versions, newest first. */
    @GetMapping("/taste/history")
    public List<TasteHistoryEntryDTO> history(
            @CurrentUser Profile caller,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "Must be between 1 and 50")
            @Max(value = 50, message = "Must be between 1 and 50") int limit) {
        return tasteProfileService.history(caller.getId(), limit);
    }
}
