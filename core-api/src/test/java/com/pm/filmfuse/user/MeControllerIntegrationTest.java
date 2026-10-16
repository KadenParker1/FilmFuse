package com.pm.filmfuse.user;

import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.support.JwtTestSupport;
import com.pm.filmfuse.user.model.AccountStatus;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Test
    void getMeAutoProvisionsProfileOnFirstCall() throws Exception {
        UUID userId = UUID.randomUUID();
        assertThat(profileRepository.findById(userId)).isEmpty();

        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUserWithName(userId, "Ada Lovelace")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.username").value(nullValue()))
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.onboardingStep").value(0))
                .andExpect(jsonPath("$.onboardingCompleted").value(false));

        assertThat(profileRepository.findById(userId)).isPresent();

        // Second call reads the same row rather than provisioning again.
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"));
        assertThat(profileRepository.count()).isPositive();
    }

    @Test
    void patchMeUpdatesProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        String username = uniqueUsername();

        mockMvc.perform(patch("/api/v1/me").with(JwtTestSupport.supabaseUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "displayName": "Film Fan", "bio": "Loves noir."}
                                """.formatted(username.toUpperCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.displayName").value("Film Fan"))
                .andExpect(jsonPath("$.bio").value("Loves noir."));

        // Omitted fields are untouched; an empty string clears.
        mockMvc.perform(patch("/api/v1/me").with(JwtTestSupport.supabaseUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.displayName").value("Film Fan"))
                .andExpect(jsonPath("$.bio").value(nullValue()));

        Profile stored = profileRepository.findById(userId).orElseThrow();
        assertThat(stored.getUsername()).isEqualTo(username);
        assertThat(stored.getBio()).isNull();
    }

    @Test
    void patchMeRejectsInvalidUsernameWithFieldErrors() throws Exception {
        mockMvc.perform(patch("/api/v1/me").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"no spaces!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.username").exists());

        mockMvc.perform(patch("/api/v1/me").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"ab\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").value("Username must be 3-30 characters"));
    }

    @Test
    void patchMeRejectsOverlongBio() throws Exception {
        mockMvc.perform(patch("/api/v1/me").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\": \"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.bio").exists());
    }

    @Test
    void patchMeReturns400ForMalformedJson() throws Exception {
        mockMvc.perform(patch("/api/v1/me").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void takenUsernameIsAConflict() throws Exception {
        String username = uniqueUsername();
        RequestPostProcessor first = someUser();
        RequestPostProcessor second = someUser();

        mockMvc.perform(patch("/api/v1/me").with(first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"" + username + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/me").with(second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"" + username + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("That username is already taken."));

        // Re-saving your own username is not a conflict.
        mockMvc.perform(patch("/api/v1/me").with(first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"" + username + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void usernameAvailability() throws Exception {
        String username = uniqueUsername();
        RequestPostProcessor owner = someUser();
        RequestPostProcessor other = someUser();

        mockMvc.perform(get("/api/v1/usernames/{u}/available", username).with(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.available").value(true));

        mockMvc.perform(patch("/api/v1/me").with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"" + username + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/usernames/{u}/available", username.toUpperCase()).with(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.available").value(false));

        mockMvc.perform(get("/api/v1/usernames/{u}/available", username).with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));

        mockMvc.perform(get("/api/v1/usernames/{u}/available", "bad-name").with(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void suspendedAccountIsForbidden() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isOk());
        Profile profile = profileRepository.findById(userId).orElseThrow();
        profile.setStatus(AccountStatus.SUSPENDED);
        profileRepository.save(profile);

        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This account is suspended."));
    }

    /** Usernames are unique across the shared test database, so each test mints its own. */
    private static String uniqueUsername() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
