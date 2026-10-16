package com.pm.filmfuse.user;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.support.JwtTestSupport;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /me/onboarding/complete. */
class OnboardingIntegrationTest extends AbstractIntegrationTest {

    private static final String COMPLETE = "/api/v1/me/onboarding/complete";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Test
    void requiresAToken() throws Exception {
        mockMvc.perform(post(COMPLETE)).andExpect(status().isUnauthorized());
    }

    @Test
    void refusesAProfileWithoutAUsername() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(post(COMPLETE).with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose a username first."));

        Profile stored = profileRepository.findById(userId).orElseThrow();
        assertThat(stored.getOnboardingCompletedAt()).isNull();
        assertThat(stored.getOnboardingStep()).isZero();
    }

    @Test
    void completesOnboardingOnceAndIsIdempotent() throws Exception {
        UUID userId = UUID.randomUUID();
        RequestPostProcessor auth = JwtTestSupport.supabaseUser(userId);
        setUsername(auth);
        Instant before = Instant.now().minusSeconds(1);

        String first = mockMvc.perform(post(COMPLETE).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.onboardingStep").value(1))
                .andReturn().getResponse().getContentAsString();
        String completedAt = JsonPath.read(first, "$.onboardingCompletedAt");
        assertThat(Instant.parse(completedAt)).isAfter(before);

        Profile stored = profileRepository.findById(userId).orElseThrow();
        assertThat(stored.getOnboardingCompletedAt()).isEqualTo(Instant.parse(completedAt));
        assertThat(stored.getOnboardingStep()).isEqualTo(1);

        // A second call keeps the original timestamp.
        mockMvc.perform(post(COMPLETE).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.onboardingCompletedAt").value(completedAt));
        mockMvc.perform(get("/api/v1/me").with(auth))
                .andExpect(jsonPath("$.onboardingCompletedAt").value(completedAt));
        assertThat(profileRepository.findById(userId).orElseThrow().getOnboardingCompletedAt())
                .isEqualTo(Instant.parse(completedAt));
    }

    @Test
    void neverLowersAHigherStep() throws Exception {
        UUID userId = UUID.randomUUID();
        RequestPostProcessor auth = JwtTestSupport.supabaseUser(userId);
        setUsername(auth);
        Profile profile = profileRepository.findById(userId).orElseThrow();
        profile.setOnboardingStep(3);
        profileRepository.save(profile);

        mockMvc.perform(post(COMPLETE).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStep").value(3))
                .andExpect(jsonPath("$.onboardingCompletedAt").value(not(nullValue())));
    }

    private void setUsername(RequestPostProcessor auth) throws Exception {
        String username = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mockMvc.perform(patch("/api/v1/me").with(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"" + username + "\"}"))
                .andExpect(status().isOk());
    }
}
