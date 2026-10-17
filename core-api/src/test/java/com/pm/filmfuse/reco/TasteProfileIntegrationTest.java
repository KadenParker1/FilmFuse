package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.ml.MlUserVector;
import com.pm.filmfuse.reco.ml.MlUserVectorResponse;
import com.pm.filmfuse.reco.model.TasteDimensions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /me/taste-profile and GET /me/taste/history. */
class TasteProfileIntegrationTest extends AbstractTasteIntegrationTest {

    private static final String PROFILE = "/api/v1/me/taste-profile";
    private static final String HISTORY = "/api/v1/me/taste/history";

    @Test
    void bothRequireAToken() throws Exception {
        mockMvc.perform(get(PROFILE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());
    }

    @Test
    void aNewUserHasNoVectorAndEveryDimensionInFixedOrder() throws Exception {
        TestUser user = user();
        mockMvc.perform(get(PROFILE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"))
                .andExpect(jsonPath("$.updatedAt").value(nullValue()))
                .andExpect(jsonPath("$.modelVersion").value(nullValue()))
                .andExpect(jsonPath("$.persona").value(nullValue()))
                .andExpect(jsonPath("$.topGenres", hasSize(0)))
                .andExpect(jsonPath("$.dimensions", hasSize(27)))
                .andExpect(jsonPath("$.dimensions[0].key").value("genre:28"))
                .andExpect(jsonPath("$.dimensions[0].label").value("Action"))
                .andExpect(jsonPath("$.dimensions[0].stated").value(nullValue()))
                .andExpect(jsonPath("$.dimensions[0].learned").value(nullValue()))
                .andExpect(jsonPath("$.dimensions[14].key").value("genre:878"))
                .andExpect(jsonPath("$.dimensions[14].label").value("Science Fiction"))
                .andExpect(jsonPath("$.dimensions[18].key").value("genre:37"))
                .andExpect(jsonPath("$.dimensions[19].key").value("mood:mind-bending"))
                .andExpect(jsonPath("$.dimensions[19].label").value("Mind-bending"))
                .andExpect(jsonPath("$.dimensions[26].key").value("mood:feel-good"))
                .andExpect(jsonPath("$.ratingsCount").value(0));
    }

    @Test
    void statedPrefsShowBeforeTheFirstBuildAndTopGenresFallBackToThemAboveNeutralOnly() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 27, \"score\": 3}, {\"genreId\": 18, \"score\": 9}, {\"genreId\": 35, \"score\": 6}]");
        mockMvc.perform(get(PROFILE).with(user.auth()))
                .andExpect(jsonPath("$.status").value("REBUILDING"))
                .andExpect(jsonPath("$.persona").value(nullValue()))
                .andExpect(jsonPath("$.topGenres[*].key").value(contains("genre:18", "genre:35")))
                .andExpect(jsonPath("$.topGenres[0].stated").value(9))
                .andExpect(jsonPath("$.topGenres[0].learned").value(nullValue()));
    }

    @Test
    void afterABuildLearnedValuesPersonaAndTopGenresComeFromTheVector() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 878, \"score\": 9}, {\"genreId\": 10749, \"score\": 8}, {\"genreId\": 27, \"score\": 1}]");
        setMoods(user, "[{\"moodTag\": \"cozy\", \"score\": 10}]");
        rate(user, movie(uniqueGenre(), "PG", 1), 7);
        buildVector(user);

        mockMvc.perform(get(PROFILE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FRESH"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.modelVersion").value("fallback-0"))
                .andExpect(jsonPath("$.persona").value("Cozy Sci-Fi Romantic"))
                // Only genres above neutral 5: the rest of the vector is neutral or below.
                .andExpect(jsonPath("$.topGenres", hasSize(2)))
                .andExpect(jsonPath("$.topGenres[0].key").value("genre:878"))
                .andExpect(jsonPath("$.topGenres[0].learned").value(9.0))
                .andExpect(jsonPath("$.topGenres[1].key").value("genre:10749"))
                .andExpect(jsonPath("$.dimensions[10].key").value("genre:27"))
                .andExpect(jsonPath("$.dimensions[10].stated").value(1))
                .andExpect(jsonPath("$.dimensions[10].learned").value(1.0))
                .andExpect(jsonPath("$.dimensions[20].key").value("mood:cozy"))
                .andExpect(jsonPath("$.dimensions[20].stated").value(10))
                .andExpect(jsonPath("$.dimensions[20].learned").value(10.0))
                .andExpect(jsonPath("$.ratingsCount").value(1));

        // A new signal: the old vector is still shown while a rebuild is pending.
        setGenres(user, "[]");
        mockMvc.perform(get(PROFILE).with(user.auth()))
                .andExpect(jsonPath("$.status").value("REBUILDING"))
                .andExpect(jsonPath("$.dimensions[14].stated").value(nullValue()))
                .andExpect(jsonPath("$.dimensions[14].learned").value(9.0));
    }

    @Test
    void nothingAboveNeutralGivesNoTopGenresAndAFallbackPersona() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 5}, {\"genreId\": 27, \"score\": 2}]");
        setMoods(user, "[{\"moodTag\": \"cozy\", \"score\": 5}]");
        buildVector(user);
        mockMvc.perform(get(PROFILE).with(user.auth()))
                .andExpect(jsonPath("$.status").value("FRESH"))
                .andExpect(jsonPath("$.topGenres", hasSize(0)))
                .andExpect(jsonPath("$.persona").value("Eclectic Film Buff"));
        mockMvc.perform(get(HISTORY).with(user.auth()))
                .andExpect(jsonPath("$[0].topGenres", hasSize(0)));
    }

    @Test
    void oneGenreAboveNeutralGivesAOneGenrePersona() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 878, \"score\": 8}]");
        setMoods(user, "[{\"moodTag\": \"cozy\", \"score\": 9}]");
        buildVector(user);
        mockMvc.perform(get(PROFILE).with(user.auth()))
                .andExpect(jsonPath("$.topGenres[*].key").value(contains("genre:878")))
                .andExpect(jsonPath("$.persona").value("Cozy Futurist"));
    }

    @Test
    void learnedValuesAreRoundedToOneDecimal() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        List<Double> values = new ArrayList<>(Collections.nCopies(TasteDimensions.INTERPRETABLE_DIM, 5.0));
        values.set(0, 7.26);
        values.set(1, 7.24);
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userVector(any())).thenReturn(new MlUserVectorResponse(new MlUserVector(values, null), 27, "owner-2"));
        buildVector(user);
        mockMvc.perform(get(PROFILE).with(user.auth()))
                .andExpect(jsonPath("$.modelVersion").value("owner-2"))
                .andExpect(jsonPath("$.dimensions[0].learned").value(7.3))
                .andExpect(jsonPath("$.dimensions[1].learned").value(7.2));
    }

    @Test
    void historyIsNewestFirstWithReasonsAndTopThreeGenres() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 53, \"score\": 9}, {\"genreId\": 80, \"score\": 8}, {\"genreId\": 9648, \"score\": 7}]");
        buildVector(user);
        rate(user, movie(uniqueGenre(), "PG", 1), 5);
        buildVector(user);
        setGenres(user, "[{\"genreId\": 16, \"score\": 10}]");
        buildVector(user);

        mockMvc.perform(get(HISTORY).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].version").value(contains(3, 2, 1)))
                .andExpect(jsonPath("$[*].reason").value(contains("TASTE_EDIT", "RATING", "TASTE_EDIT")))
                .andExpect(jsonPath("$[0].modelVersion").value("fallback-0"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].topGenres", hasSize(1)))
                .andExpect(jsonPath("$[0].topGenres[0].key").value("genre:16"))
                .andExpect(jsonPath("$[0].topGenres[0].stated").value(nullValue()))
                .andExpect(jsonPath("$[0].topGenres[0].learned").value(10.0))
                .andExpect(jsonPath("$[2].topGenres[*].key").value(contains("genre:53", "genre:80", "genre:9648")));

        mockMvc.perform(get(HISTORY).param("limit", "1").with(user.auth()))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].version").value(3));
        mockMvc.perform(get(HISTORY).param("limit", "0").with(user.auth())).andExpect(status().isBadRequest());
        mockMvc.perform(get(HISTORY).param("limit", "51").with(user.auth())).andExpect(status().isBadRequest());
        mockMvc.perform(get(HISTORY).with(user().auth())).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }
}
