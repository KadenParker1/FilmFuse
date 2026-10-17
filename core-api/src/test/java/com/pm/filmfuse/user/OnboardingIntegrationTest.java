package com.pm.filmfuse.user;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.reco.AbstractTasteIntegrationTest;
import com.pm.filmfuse.reco.ml.MlServiceException;
import com.pm.filmfuse.reco.service.FallbackVectorBuilder;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.pm.filmfuse.reco.ml.MlUserVector;
import com.pm.filmfuse.reco.ml.MlUserVectorRequest;
import com.pm.filmfuse.reco.ml.MlUserVectorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /me/onboarding, PUT /me/onboarding/step, POST /me/onboarding/complete. */
class OnboardingIntegrationTest extends AbstractTasteIntegrationTest {

    private static final String STATE = "/api/v1/me/onboarding";
    private static final String STEP = "/api/v1/me/onboarding/step";
    private static final String COMPLETE = "/api/v1/me/onboarding/complete";

    @Autowired
    private ProfileRepository profileRepository;

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        mockMvc.perform(get(STATE)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(STEP).contentType(MediaType.APPLICATION_JSON).content("{\"step\": 1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(COMPLETE)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------- state / step

    @Test
    void aNewUsersStateIsEmptyWithDefaultComfort() throws Exception {
        TestUser user = user();
        mockMvc.perform(get(STATE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value(0))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.genrePrefs", hasSize(0)))
                .andExpect(jsonPath("$.moodPrefs", hasSize(0)))
                .andExpect(jsonPath("$.favorites", hasSize(0)))
                .andExpect(jsonPath("$.calibrationRatedCount").value(0))
                .andExpect(jsonPath("$.comfort.maxCertification").value(nullValue()))
                .andExpect(jsonPath("$.comfort.allowUnrated").value(false));
    }

    @Test
    void stateReturnsEverythingSavedSoFar() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int fav = movie(genre, "PG", 1);
        int swiped = movie(genre, "PG", 1);
        int unseen = movie(genre, "PG", 1);
        int quick = movie(genre, "PG", 1);
        setGenres(user, "[{\"genreId\": 878, \"score\": 9}, {\"genreId\": 28, \"score\": 3}]");
        setMoods(user, "[{\"moodTag\": \"feel-good\", \"score\": 7}, {\"moodTag\": \"cozy\", \"score\": 8}]");
        setFavorites(user, fav);
        postJson(user, "/api/v1/me/ratings/batch", "[{\"movieId\": " + swiped + ", \"score\": 6},"
                + " {\"movieId\": " + unseen + ", \"unseen\": true}]").andExpect(status().isNoContent());
        rate(user, quick, 9);
        setComfort(user, "{\"maxCertification\": \"PG_13\"}");

        mockMvc.perform(get(STATE).with(user.auth()))
                .andExpect(status().isOk())
                // Fixed vector order (28 before 878; cozy before feel-good), not request order.
                .andExpect(jsonPath("$.genrePrefs[0].genreId").value(28))
                .andExpect(jsonPath("$.genrePrefs[0].score").value(3))
                .andExpect(jsonPath("$.genrePrefs[1].genreId").value(878))
                .andExpect(jsonPath("$.moodPrefs[0].moodTag").value("cozy"))
                .andExpect(jsonPath("$.moodPrefs[1].moodTag").value("feel-good"))
                .andExpect(jsonPath("$.favorites[0].rank").value(1))
                .andExpect(jsonPath("$.favorites[0].movie.id").value(fav))
                // Only scored swipes count (not "haven't seen", not quick rates).
                .andExpect(jsonPath("$.calibrationRatedCount").value(1))
                .andExpect(jsonPath("$.comfort.maxCertification").value("PG_13"));
    }

    @Test
    void theStepOnlyMovesForward() throws Exception {
        TestUser user = user();
        putJson(user, STEP, "{\"step\": 4}").andExpect(status().isOk()).andExpect(jsonPath("$.step").value(4));
        putJson(user, STEP, "{\"step\": 2}").andExpect(status().isOk()).andExpect(jsonPath("$.step").value(4));
        mockMvc.perform(get(STATE).with(user.auth())).andExpect(jsonPath("$.step").value(4));
        assertThat(profileRepository.findById(user.id()).orElseThrow().getOnboardingStep()).isEqualTo(4);
    }

    @Test
    void stepIsValidated() throws Exception {
        TestUser user = user();
        putJson(user, STEP, "{\"step\": 9}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.step").value("Must be between 0 and 8"));
        putJson(user, STEP, "{\"step\": -1}").andExpect(status().isBadRequest());
        putJson(user, STEP, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.step").value("Step is required"));
    }

    // ------------------------------------------------------------------------------- complete

    @Test
    void completionRequiresAUsernameFirst() throws Exception {
        TestUser user = userWithoutUsername();
        qualifyForCompletion(user);
        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose a username first."));
        Profile stored = profileRepository.findById(user.id()).orElseThrow();
        assertThat(stored.getOnboardingCompletedAt()).isNull();
        assertThat(stored.getOnboardingStep()).isZero();
    }

    @Test
    void completionRequiresFiveGenresWithThreeLoved() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        setFavorites(user, movie(genre, "PG", 1), movie(genre, "PG", 1), movie(genre, "PG", 1));
        String genresMessage = "Score at least 5 genres, with at least 3 of them 8 or higher.";

        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(genresMessage));
        // Five genres but only two loved.
        setGenres(user, """
                [{"genreId": 28, "score": 9}, {"genreId": 12, "score": 8}, {"genreId": 16, "score": 7},
                 {"genreId": 35, "score": 2}, {"genreId": 80, "score": 5}]""");
        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(genresMessage));
        // Three loved, but only four genres.
        setGenres(user, """
                [{"genreId": 28, "score": 9}, {"genreId": 12, "score": 8}, {"genreId": 16, "score": 10},
                 {"genreId": 35, "score": 2}]""");
        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(genresMessage));

        setGenres(user, QUALIFYING_GENRES);
        mockMvc.perform(post(COMPLETE).with(user.auth())).andExpect(status().isOk());
    }

    @Test
    void completionRequiresThreeFavorites() throws Exception {
        TestUser user = user();
        setGenres(user, QUALIFYING_GENRES);
        int genre = uniqueGenre();
        setFavorites(user, movie(genre, "PG", 1), movie(genre, "PG", 1));
        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pick at least 3 all-time favourites."));
        assertThat(profileRepository.findById(user.id()).orElseThrow().getOnboardingCompletedAt()).isNull();
    }

    @Test
    void completesOnceAndIsIdempotent() throws Exception {
        TestUser user = user();
        qualifyForCompletion(user);
        Instant before = Instant.now().minusSeconds(1);

        String first = json(mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id().toString()))
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.onboardingStep").value(8)));
        String completedAt = JsonPath.read(first, "$.onboardingCompletedAt");
        assertThat(Instant.parse(completedAt)).isAfter(before);
        assertThat(profileRepository.findById(user.id()).orElseThrow().getOnboardingCompletedAt())
                .isEqualTo(Instant.parse(completedAt));

        // A second call keeps the original timestamp, even after the prefs no longer qualify.
        setGenres(user, "[]");
        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompletedAt").value(completedAt));
        mockMvc.perform(get("/api/v1/me").with(user.auth()))
                .andExpect(jsonPath("$.onboardingCompletedAt").value(completedAt));
        mockMvc.perform(get(STATE).with(user.auth())).andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void interimUsersWhoCompletedWithoutPrefsKeepAccess() throws Exception {
        TestUser user = user();
        Instant earlier = Instant.parse("2026-09-01T10:00:00Z");
        jdbc.update("update app.profiles set onboarding_completed_at = ?, onboarding_step = 1 where id = ?",
                Timestamp.from(earlier), user.id());

        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.onboardingCompletedAt").value(earlier.toString()))
                .andExpect(jsonPath("$.onboardingStep").value(1));
        assertThat(vectorRow(user)).isNull();
    }

    @Test
    void completionNeverLowersAHigherStepAndMovesALowerOneToTheEnd() throws Exception {
        TestUser user = user();
        qualifyForCompletion(user);
        putJson(user, STEP, "{\"step\": 3}").andExpect(status().isOk());
        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStep").value(8))
                .andExpect(jsonPath("$.onboardingCompletedAt").value(not(nullValue())));
    }

    @org.junit.jupiter.api.AfterEach
    void waitForBackgroundBuilds() throws InterruptedException {
        awaitBuildsIdle();
    }

    @Test
    void completionBuildsTheFirstVectorInTheBackgroundWithReasonOnboarding() throws Exception {
        TestUser user = user();
        qualifyForCompletion(user);
        assertThat(dirtyReason(user)).isEqualTo("TASTE_EDIT");

        mockMvc.perform(post(COMPLETE).with(user.auth())).andExpect(status().isOk());

        awaitVersion(user, 1);
        assertThat(isDirty(user)).isFalse();
        assertThat(vectorRow(user).get("model_version")).isEqualTo(FallbackVectorBuilder.MODEL_VERSION);
        assertThat(jdbc.queryForObject("select reason from app.user_interest_vector_history where user_id = ? and version = 1",
                String.class, user.id())).isEqualTo("ONBOARDING");
        mockMvc.perform(get("/api/v1/me/taste-profile").with(user.auth()))
                .andExpect(jsonPath("$.status").value("FRESH"))
                .andExpect(jsonPath("$.persona").value(not(nullValue())));
    }

    @Test
    void completionReturnsBeforeTheBuildWhichFinishesAfterwards() throws Exception {
        TestUser user = user();
        qualifyForCompletion(user);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userVector(any())).thenAnswer(invocation -> {
            MlUserVectorRequest request = invocation.getArgument(0);
            if (request != null && request.userId().equals(user.id())) {
                started.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return new MlUserVectorResponse(new MlUserVector(Collections.nCopies(27, 6.0), null), 27, "owner-async");
        });
        try {
            // The ML call is blocked, yet the request completes.
            mockMvc.perform(post(COMPLETE).with(user.auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.onboardingCompleted").value(true));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(version(user)).isZero();
            mockMvc.perform(get("/api/v1/me/taste-profile").with(user.auth()))
                    .andExpect(jsonPath("$.status").value("REBUILDING"));
        } finally {
            release.countDown();
        }
        awaitVersion(user, 1);
        assertThat(vectorRow(user).get("model_version")).isEqualTo("owner-async");
        mockMvc.perform(get("/api/v1/me/taste-profile").with(user.auth()))
                .andExpect(jsonPath("$.status").value("FRESH"));
    }

    @Test
    void anMlFailureOnTheBackgroundBuildStillGivesANewUserTheFallbackVector() throws Exception {
        TestUser user = user();
        qualifyForCompletion(user);
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userVector(any())).thenThrow(new MlServiceException("down"));

        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true));
        awaitVersion(user, 1);
        assertThat(vectorRow(user).get("model_version")).isEqualTo(FallbackVectorBuilder.MODEL_VERSION);
    }
}
