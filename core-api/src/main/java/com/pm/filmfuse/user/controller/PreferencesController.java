package com.pm.filmfuse.user.controller;

import com.pm.filmfuse.user.dto.FavoriteDTO;
import com.pm.filmfuse.user.dto.FavoriteMovieDTO;
import com.pm.filmfuse.user.dto.GenrePrefDTO;
import com.pm.filmfuse.user.dto.MoodPrefDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import com.pm.filmfuse.user.service.TastePreferencesService;
import java.util.List;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's stated taste (onboarding steps 2, 3 and 5, and the Taste editor). Each PUT replaces
 * the whole list and marks the vector dirty; the current values are in {@code GET /me/onboarding}
 * and {@code GET /me/taste-profile}.
 */
@RestController
@RequestMapping("/api/v1/me/preferences")
public class PreferencesController {

    private final TastePreferencesService preferences;

    public PreferencesController(TastePreferencesService preferences) {
        this.preferences = preferences;
    }

    /** 400 on an unknown genre, a duplicate or a score outside 1-10. */
    @PutMapping("/genres")
    public List<GenrePrefDTO> genres(@CurrentUser Profile caller, @RequestBody List<GenrePrefDTO> request) {
        return preferences.replaceGenrePrefs(caller.getId(), request);
    }

    /** 400 on an unknown mood tag, a duplicate or a score outside 1-10. */
    @PutMapping("/moods")
    public List<MoodPrefDTO> moods(@CurrentUser Profile caller, @RequestBody List<MoodPrefDTO> request) {
        return preferences.replaceMoodPrefs(caller.getId(), request);
    }

    /** 0-10 favourites, unique movies and ranks. 404 for a movie the catalog does not know. */
    @PutMapping("/favorites")
    public List<FavoriteMovieDTO> favorites(@CurrentUser Profile caller, @RequestBody List<FavoriteDTO> request) {
        return preferences.replaceFavorites(caller.getId(), request);
    }
}
