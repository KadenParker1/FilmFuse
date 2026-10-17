package com.pm.filmfuse.social;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.rating.AbstractPhase5IntegrationTest;
import com.pm.filmfuse.social.service.ClosenessJob;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ClosenessJob (contract section B) against real sessions, watch history and vectors:
 * {@code clamp(0.4 + 0.35 * min(1, coSessions90d / 5) + 0.25 * tasteSimilarity, 0, 1)}.
 */
class ClosenessJobIntegrationTest extends AbstractPhase5IntegrationTest {

    private static final double NEUTRAL = 0.4 + 0.25 * 0.5;

    @Autowired
    private ClosenessJob closenessJob;

    private double score(TestUser from, TestUser to) {
        Float score = jdbc.queryForObject("select score from app.friend_closeness where user_id = ? and friend_id = ?",
                Float.class, from.id(), to.id());
        return score == null ? Double.NaN : score;
    }

    private double apiCloseness(TestUser viewer, TestUser friend) throws Exception {
        String body = json(mockMvc.perform(get("/api/v1/friends").with(viewer.auth())).andExpect(status().isOk()));
        List<Number> values = JsonPath.read(body, "$.items[?(@.id == '" + friend.id() + "')].closeness");
        return values.getFirst().doubleValue();
    }

    private void setVector(TestUser user, float... values) {
        StringBuilder literal = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            literal.append(i == 0 ? "" : ",").append(values[i]);
        }
        jdbc.update("""
                insert into app.user_interest_vectors (user_id, interpretable, dim, model_version, version, dirty,
                                                       dirty_reason, dirty_since, updated_at)
                values (?, ?::real[], ?, 'test', 1, false, null, null, now())
                on conflict (user_id) do update set interpretable = excluded.interpretable, dim = excluded.dim,
                    version = 1, dirty = false, dirty_reason = null, dirty_since = null
                """, user.id(), literal.append("}").toString(), values.length);
    }

    private void assertBoth(TestUser a, TestUser b, double expected) {
        assertThat(score(a, b)).isCloseTo(expected, within(1e-5));
        assertThat(score(b, a)).isCloseTo(expected, within(1e-5));
    }

    @Test
    void friendsWithNoSessionsAndNoVectorsGetTheNeutralScore() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        jdbc.update("update app.friend_closeness set score = 0.9 where user_id in (?, ?)", a.id(), b.id());

        assertThat(closenessJob.recomputeAll()).isGreaterThanOrEqualTo(2);

        assertBoth(a, b, NEUTRAL);
        assertThat(apiCloseness(a, b)).isCloseTo(NEUTRAL, within(1e-5));
    }

    @Test
    void sessionsWatchedTogetherInTheLast90DaysCount() throws Exception {
        TestUser a = user();
        TestUser b = user();
        TestUser c = user();
        befriend(a, b);
        befriend(a, c);
        befriend(b, c);
        watchAdHoc(a, b);
        watchAdHoc(a, b, c);
        watchAdHoc(a, c);
        // An open (never picked) session is not watching together.
        int genre = uniqueGenre();
        movie(genre, "PG", 1);
        sessionId(createAdHocSession(a, genre, b));

        closenessJob.recomputeAll();

        assertBoth(a, b, 0.4 + 0.35 * 2 / 5 + 0.125);
        assertBoth(a, c, 0.4 + 0.35 * 2 / 5 + 0.125);
        assertBoth(b, c, 0.4 + 0.35 * 1 / 5 + 0.125);
    }

    @Test
    void sessionsOlderThan90DaysDropOut() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        watchAdHoc(a, b);
        CLOCK.advance(Duration.ofDays(60));
        watchAdHoc(a, b);
        CLOCK.advance(Duration.ofDays(31));

        closenessJob.recomputeAll();
        assertBoth(a, b, 0.4 + 0.35 / 5 + 0.125);
    }

    @Test
    void coSessionsSaturateAtFive() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        for (int i = 0; i < 6; i++) {
            watchAdHoc(i % 2 == 0 ? a : b, i % 2 == 0 ? b : a);
        }

        closenessJob.recomputeAll();
        assertBoth(a, b, 0.4 + 0.35 + 0.125);
    }

    @Test
    void tasteSimilarityUsesBothVectorsOrIsNeutralWithoutOne() throws Exception {
        TestUser a = user();
        TestUser same = user();
        TestUser opposite = user();
        TestUser noVector = user();
        TestUser unbuilt = user();
        befriend(a, same);
        befriend(a, opposite);
        befriend(a, noVector);
        befriend(a, unbuilt);
        // 1-10 scale; similarity is measured on offsets from the neutral 5.
        setVector(a, 6, 5, 5);
        setVector(same, 8, 5, 5);
        setVector(opposite, 4, 5, 5);
        // A row that is only dirty (version 0) is not a vector.
        jdbc.update("""
                insert into app.user_interest_vectors (user_id, dirty, dirty_reason, dirty_since)
                values (?, true, 'RATING', now())
                """, unbuilt.id());

        closenessJob.recomputeAll();

        assertBoth(a, same, 0.4 + 0.25);
        assertBoth(a, opposite, 0.4);
        assertBoth(a, noVector, NEUTRAL);
        assertBoth(a, unbuilt, NEUTRAL);
    }

    @Test
    void sessionsAndTasteCombineUpToOne() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        setVector(a, 9, 7, 3);
        setVector(b, 7, 6, 4);
        for (int i = 0; i < 5; i++) {
            watchAdHoc(a, b);
        }

        closenessJob.recomputeAll();
        assertBoth(a, b, 1.0);
    }

    @Test
    void theExplicitFlagIsKeptAndStillRaisesTheReportedClosenessToPointEight() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        mockMvc.perform(patch("/api/v1/friends/{id}", b.id()).with(a.auth())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"closeFriend\": true}"))
                .andExpect(status().isOk());

        closenessJob.recomputeAll();

        assertBoth(a, b, NEUTRAL);
        assertThat(jdbc.queryForObject("select explicit_close from app.friend_closeness where user_id = ? and friend_id = ?",
                Boolean.class, a.id(), b.id())).isTrue();
        assertThat(apiCloseness(a, b)).isCloseTo(0.8, within(1e-5));
        assertThat(apiCloseness(b, a)).isCloseTo(NEUTRAL, within(1e-5));

        for (int i = 0; i < 5; i++) {
            watchAdHoc(a, b);
        }
        closenessJob.recomputeAll();
        assertThat(apiCloseness(a, b)).isCloseTo(0.875, within(1e-5));
    }

    @Test
    void endedFriendshipsAndPendingRequestsGetNoRows() throws Exception {
        TestUser a = user();
        TestUser b = user();
        TestUser pending = user();
        befriend(a, b);
        watchAdHoc(a, b);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/friends/{id}", b.id()).with(a.auth())).andExpect(status().isNoContent());
        sendRequest(a, pending).andExpect(status().isOk());

        closenessJob.recomputeAll();

        assertThat(closenessRows(a, b)).isZero();
        assertThat(closenessRows(a, pending)).isZero();
    }
}
