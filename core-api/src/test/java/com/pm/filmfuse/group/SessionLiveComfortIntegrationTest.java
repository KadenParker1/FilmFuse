package com.pm.filmfuse.group;

import com.pm.filmfuse.social.service.SocialGraphService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comfort settings stay hard constraints while a session is OPEN: tightening them (or the group cap)
 * hides results that no longer pass and makes them unpickable; DECIDED sessions are history.
 * Also: the batched closeness matrix agrees with the per-pair closeness.
 */
class SessionLiveComfortIntegrationTest extends AbstractGroupIntegrationTest {

    private static final String OUTSIDE = "This movie is outside the group's comfort settings.";

    @Autowired
    private SocialGraphService graph;

    @Test
    void aMemberTighteningTheirSettingsHidesAndBlocksOverLimitResults() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int rated = movie(genre, "R", 20);
        int pg13 = movie(genre, "PG-13", 10);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        assertThat(resultIds(json(getSession(a, sessionId)))).containsExactly(rated, pg13);

        setComfort(b, "{\"maxCertification\": \"PG_13\", \"allowUnrated\": true}");

        String body = json(getSession(a, sessionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveFilter.maxCertification").value("PG_13"))
                .andExpect(jsonPath("$.effectiveFilter.label").value("Rated PG-13 or below")));
        assertThat(resultIds(body)).containsExactly(pg13);
        // Every SessionDTO response applies it, e.g. a vote.
        assertThat(resultIds(json(vote(a, sessionId, pg13, "UP").andExpect(status().isOk())))).containsExactly(pg13);

        pick(a, sessionId, rated).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(OUTSIDE));
        getSession(a, sessionId).andExpect(jsonPath("$.status").value("OPEN"));
        pick(a, sessionId, pg13).andExpect(status().isOk());
    }

    @Test
    void theOwnerSettingAGroupCapHidesAndBlocksOverLimitResults() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = activeGroup(owner, member);
        int genre = uniqueGenre();
        int pg13 = movie(genre, "PG-13", 20);
        int pg = movie(genre, "PG", 10);
        UUID sessionId = sessionId(createGroupSession(member, groupId, genre));

        mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comfortCap\": {\"maxCertification\": \"PG\"}}"))
                .andExpect(status().isOk());

        String body = json(getSession(member, sessionId)
                .andExpect(jsonPath("$.effectiveFilter.maxCertification").value("PG")));
        assertThat(resultIds(body)).containsExactly(pg);
        pick(member, sessionId, pg13).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(OUTSIDE));
    }

    @Test
    void looseningAgainShowsOnlyWhatTheCurrentFilterAllows() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int rated = movie(genre, "R", 30);
        int unrated = movie(genre, null, 20);
        int pg = movie(genre, "PG", 10);
        setComfort(a, "{\"maxCertification\": null, \"allowUnrated\": true}");
        setComfort(b, "{\"maxCertification\": null, \"allowUnrated\": true}");
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        assertThat(resultIds(json(getSession(a, sessionId)))).containsExactly(rated, unrated, pg);

        setComfort(b, "{\"maxCertification\": \"PG\", \"allowUnrated\": false}");
        assertThat(resultIds(json(getSession(a, sessionId)))).containsExactly(pg);

        // Loosen the certification but keep refusing unrated: R comes back, the unrated one does not.
        setComfort(b, "{\"maxCertification\": \"R\", \"allowUnrated\": false}");
        assertThat(resultIds(json(getSession(a, sessionId)))).containsExactly(rated, pg);
        pick(a, sessionId, unrated).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(OUTSIDE));

        setComfort(b, "{\"maxCertification\": null, \"allowUnrated\": true}");
        assertThat(resultIds(json(getSession(a, sessionId)))).containsExactly(rated, unrated, pg);
    }

    @Test
    void dimensionLimitsApplyMidSessionToo() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int violent = movie(genre, "R", 20, java.util.Map.of("violence", "strong"));
        int calm = movie(genre, "R", 10, java.util.Map.of("violence", "none"));
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));

        setComfort(a, "{\"contentLimits\": {\"VIOLENCE\": \"MILD\"}}");

        assertThat(resultIds(json(getSession(b, sessionId)))).containsExactly(calm);
        pick(b, sessionId, violent).andExpect(status().isBadRequest());
    }

    @Test
    void decidedSessionsAreShownAsStored() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int rated = movie(genre, "R", 20);
        int pg = movie(genre, "PG", 10);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        pick(a, sessionId, rated).andExpect(status().isOk());

        setComfort(b, "{\"maxCertification\": \"G\", \"allowUnrated\": true}");

        String body = json(getSession(b, sessionId)
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.effectiveFilter.label").value("No limits"))
                .andExpect(jsonPath("$.pickedMovie.id").value(rated)));
        assertThat(resultIds(body)).containsExactly(rated, pg);
    }

    @Test
    void expiredSessionsAreShownAsStored() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int rated = movie(genre, "R", 20);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        CLOCK.advance(java.time.Duration.ofHours(25));
        setComfort(b, "{\"maxCertification\": \"G\", \"allowUnrated\": true}");

        assertThat(resultIds(json(getSession(b, sessionId).andExpect(jsonPath("$.status").value("EXPIRED")))))
                .containsExactly(rated);
    }

    // ------------------------------------------------------------------------- closeness matrix

    @Test
    void theClosenessMatrixMatchesPerPairClosenessForAMixedGraph() throws Exception {
        TestUser a = user();
        TestUser b = user();
        TestUser c = user();
        TestUser d = user();
        befriend(a, b);
        befriend(a, c);
        befriend(b, c);
        sendRequest(c, d).andExpect(status().isOk()); // pending: not friends
        // a marks b a close friend (one direction only); b-c implicit score raised above 0.8 on one side.
        mockMvc.perform(patch("/api/v1/friends/{id}", b.id()).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"closeFriend\": true}")).andExpect(status().isOk());
        jdbc.update("update app.friend_closeness set score = 0.9 where user_id = ? and friend_id = ?", b.id(), c.id());
        jdbc.update("update app.friend_closeness set score = 0.3 where user_id = ? and friend_id = ?", c.id(), a.id());

        List<UUID> members = List.of(a.id(), b.id(), c.id(), d.id());
        double[][] matrix = graph.closenessMatrix(members);

        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                assertThat(matrix[i][j]).as("[%d][%d]", i, j)
                        .isEqualTo(graph.closeness(members.get(i), members.get(j)));
            }
        }
        assertThat(matrix[0][1]).isEqualTo(0.8);
        assertThat(matrix[1][0]).isEqualTo(0.5);
        assertThat(matrix[1][2]).isEqualTo(0.9);
        assertThat(matrix[2][0]).isEqualTo(0.3);
        assertThat(matrix[2][3]).isZero();
        assertThat(matrix[3][3]).isZero();
        assertThat(graph.closenessMatrix(List.of())).isEmpty();
    }

    @Test
    void sessionsSendTheBatchedMatrixToTheRanker() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        mockMvc.perform(patch("/api/v1/friends/{id}", b.id()).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"closeFriend\": true}")).andExpect(status().isOk());
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        org.mockito.Mockito.when(mlClient.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(mlClient.groupRecommendations(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new com.pm.filmfuse.reco.ml.MlGroupRecommendation(movie, 7, java.util.Map.of(), "x")));

        mockMvc.perform(post("/api/v1/sessions").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\": [\"" + b.id() + "\"], \"filters\": {\"genreIds\": [" + genre + "]}}"))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(com.pm.filmfuse.reco.ml.MlGroupRecommendationRequest.class);
        org.mockito.Mockito.verify(mlClient).groupRecommendations(captor.capture());
        assertThat(captor.getValue().closeness()).containsExactly(List.of(0.0, 0.8), List.of(0.5, 0.0));
    }
}
