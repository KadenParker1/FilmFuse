package com.pm.filmfuse.reco;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.reco.ml.MlCalibrationCandidatesRequest;
import com.pm.filmfuse.reco.ml.MlServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /me/calibration/next. */
class CalibrationIntegrationTest extends AbstractTasteIntegrationTest {

    private static final String NEXT = "/api/v1/me/calibration/next";

    private List<Integer> next(TestUser user, int count) throws Exception {
        return JsonPath.read(json(mockMvc.perform(get(NEXT).param("count", String.valueOf(count)).with(user.auth()))
                .andExpect(status().isOk())), "$[*].id");
    }

    /** A PG-13 user who rated, skipped and favourited the three most popular action movies. */
    private record Setup(TestUser user, int rated, int unseen, int favorite, int rRated, int eligible) {
    }

    private Setup setup() throws Exception {
        TestUser user = user();
        setComfort(user, "{\"maxCertification\": \"PG_13\"}");
        int eligible = topMovie(28, "PG");
        int rRated = topMovie(28, "R");
        int favorite = topMovie(28, "PG");
        int unseen = topMovie(28, "PG");
        int rated = topMovie(28, "PG");
        rate(user, rated, 6);
        postJson(user, "/api/v1/me/ratings/batch", "[{\"movieId\": " + unseen + ", \"unseen\": true}]")
                .andExpect(status().isNoContent());
        setFavorites(user, favorite);
        return new Setup(user, rated, unseen, favorite, rRated, eligible);
    }

    @Test
    void requiresAToken() throws Exception {
        mockMvc.perform(get(NEXT)).andExpect(status().isUnauthorized());
    }

    @Test
    void countIsValidated() throws Exception {
        TestUser user = user();
        mockMvc.perform(get(NEXT).param("count", "0").with(user.auth())).andExpect(status().isBadRequest());
        mockMvc.perform(get(NEXT).param("count", "31").with(user.auth())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.count").value("Must be between 1 and 30"));
        mockMvc.perform(get(NEXT).with(user.auth())).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(10)));
    }

    @Test
    void theFallbackSkipsSeenAndOverComfortMovies() throws Exception {
        Setup s = setup();
        // Action is the first genre of the round-robin, so the most popular eligible action movie leads.
        assertThat(next(s.user(), 1)).containsExactly(s.eligible());
        assertThat(next(s.user(), 30)).doesNotContain(s.rated(), s.unseen(), s.favorite(), s.rRated());
        verify(mlClient, never()).calibrationCandidates(any());
    }

    @Test
    void theFallbackRoundRobinsAcrossGenres() throws Exception {
        TestUser user = user();
        int action1 = topMovie(28, "PG");
        int action2 = topMovie(28, "PG");
        int adventure = topMovie(12, "PG");
        int animation = topMovie(16, "PG");
        // The most popular movie of each genre in GENRE_IDS order (28, 12, 16, ...), not two action movies.
        assertThat(next(user, 3)).containsExactly(action2, adventure, animation).doesNotContain(action1);
    }

    @Test
    void theMlServicePicksAndItsAnswerIsReChecked() throws Exception {
        Setup s = setup();
        int mlPick = topMovie(uniqueGenre(), "PG-13");
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.calibrationCandidates(any())).thenReturn(List.of(s.rated(), s.rRated(), 2147000005, mlPick));

        assertThat(next(s.user(), 1)).containsExactly(mlPick);
        ArgumentCaptor<MlCalibrationCandidatesRequest> captor = ArgumentCaptor.forClass(MlCalibrationCandidatesRequest.class);
        verify(mlClient).calibrationCandidates(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(s.user().id());
        assertThat(captor.getValue().k()).isEqualTo(1);
        assertThat(captor.getValue().seenMovieIds()).contains(s.rated(), s.unseen(), s.favorite())
                .isSortedAccordingTo(Integer::compare);
    }

    @Test
    void aShortMlAnswerIsToppedUpFromTheFallback() throws Exception {
        Setup s = setup();
        int mlPick = topMovie(uniqueGenre(), "PG-13");
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.calibrationCandidates(any())).thenReturn(List.of(mlPick));
        assertThat(next(s.user(), 2)).containsExactly(mlPick, s.eligible());
    }

    @Test
    void anMlFailureUsesTheFallback() throws Exception {
        Setup s = setup();
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.calibrationCandidates(any())).thenThrow(new MlServiceException("down"));
        assertThat(next(s.user(), 1)).containsExactly(s.eligible());
    }
}
