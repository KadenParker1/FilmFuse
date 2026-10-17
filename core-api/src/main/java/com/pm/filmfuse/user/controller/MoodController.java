package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.MoodDTO;
import com.pm.filmfuse.user.service.TastePreferencesService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/moods")
public class MoodController {

    /** The mood ("vibe") dimensions, in the fixed vector order. */
    @GetMapping
    public List<MoodDTO> list() {
        return TastePreferencesService.moods();
    }
}
