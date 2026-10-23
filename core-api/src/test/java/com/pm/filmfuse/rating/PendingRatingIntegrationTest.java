package com.pm.filmfuse.rating;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /me/pending-ratings and POST / DELETE /me/pending-ratings/{movieId}/dismiss (contract section A). */
class PendingRatingIntegrationTest extends AbstractPhase5IntegrationTest {

    private List<Integer> pendingMovieIds(TestUser user) throws Exception {
        return JsonPath.read(json(pendingRatings(user).andExpect(status().isOk())), "$[*].movie.id");
    }

    @Test
    void aPickPromptsEveryMemberWithTheSessionAndPickTime() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        Watched watched = watchAdHoc(a, b);

        for (TestUser member : List.of(a, b)) {
            pendingRatings(member).andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].movie.id").value(watched.movieId()))
                    .andExpect(jsonPath("$[0].movie.title").value("Movie " + watched.movieId()))
                    .andExpect(jsonPath("$[0].sessionId").value(watched.sessionId().toString()))
                    .andExpect(jsonPath("$[0].groupName").value(nullValue()))
                    .andExpect(jsonPath("$[0].pickedAt").isString());
        }
        String pickedAt = JsonPath.read(json(pendingRatings(a)), "$[0].pickedAt");
        String decidedAt = jdbc.queryForObject(
                "select to_char(decided_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS') from app.rec_sessions where id = ?",
                String.class, watched.sessionId());
        assertThat(pickedAt).startsWith(decidedAt);
    }

    @Test
    void aGroupSessionCarriesTheGroupName() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = activeGroup(owner, member);
        Watched watched = watchInGroup(member, groupId);

        pendingRatings(owner).andExpect(jsonPath("$[0].movie.id").value(watched.movieId()))
                .andExpect(jsonPath("$[0].groupName").value("Movie night"));
    }

    @Test
    void nonMembersAndUsersWithNoHistoryGetNothing() throws Exception {
        TestUser a = user();
        TestUser b = user();
        TestUser outsider = user();
        befriend(a, b);
        befriend(a, outsider);
        watchAdHoc(a, b);

        pendingRatings(outsider).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        pendingRatings(user()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void onlyTheLastFourteenDaysCount() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        Watched old = watchAdHoc(a, b);
        CLOCK.advance(Duration.ofDays(13));
        Watched recent = watchAdHoc(a, b);

        assertThat(pendingMovieIds(a)).containsExactly(recent.movieId(), old.movieId());
        CLOCK.advance(Duration.ofDays(1).plusMinutes(1));
        assertThat(pendingMovieIds(a)).containsExactly(recent.movieId());
        CLOCK.advance(Duration.ofDays(14));
        assertThat(pendingMovieIds(a)).isEmpty();
    }

    @Test
    void newestFirstAndAtMostTen() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        List<Integer> watchedOrder = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            watchedOrder.add(watchAdHoc(a, b).movieId());
        }

        List<Integer> expected = new ArrayList<>(watchedOrder.reversed().subList(0, 10));
        assertThat(pendingMovieIds(a)).containsExactlyElementsOf(expected);
    }

    @Test
    void ratingByAnyRouteRemovesThePrompt() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        Watched postWatch = watchAdHoc(a, b);
        Watched quick = watchAdHoc(a, b);
        Watched swiped = watchAdHoc(a, b);
        Watched kept = watchAdHoc(a, b);

        rate(a, postWatch.movieId(), "{\"score\": 9, \"source\": \"POST_WATCH\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("POST_WATCH"));
        rate(a, quick.movieId(), 6);
        swipe(a, swiped.movieId(), 4);

        assertThat(pendingMovieIds(a)).containsExactly(kept.movieId());
        // Only the rater's own prompt goes away.
        assertThat(pendingMovieIds(b)).containsExactly(kept.movieId(), swiped.movieId(), quick.movieId(),
                postWatch.movieId());
    }

    @Test
    void removingTheRatingBringsThePromptBackWhileInTheWindow() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        Watched watched = watchAdHoc(a, b);
        rate(a, watched.movieId(), 7);
        assertThat(pendingMovieIds(a)).isEmpty();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/ratings/{id}", watched.movieId()).with(a.auth())).andExpect(status().isNoContent());
        assertThat(pendingMovieIds(a)).containsExactly(watched.movieId());
    }

    @Test
    void dismissIsStoredIdempotentAndPermanent() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        Watched first = watchAdHoc(a, b);
        Watched second = watchAdHoc(a, b);

        dismissPending(a, first.movieId()).andExpect(status().isNoContent());
        dismissPending(a, first.movieId()).andExpect(status().isNoContent());
        assertThat(rows("select count(*) from app.pending_rating_dismissals where user_id = ? and movie_id = ?",
                a.id(), first.movieId())).isEqualTo(1);
        assertThat(pendingMovieIds(a)).containsExactly(second.movieId());
        assertThat(pendingMovieIds(b)).containsExactly(second.movieId(), first.movieId());

        // Watching the same movie again later never brings a dismissed prompt back.
        int genre = uniqueGenre();
        jdbc.update("update app.movies set genre_ids = array[?]::int[] where id = ?", genre, first.movieId());
        UUID again = sessionId(createAdHocSession(a, genre, b));
        CLOCK.advance(Duration.ofSeconds(1));
        pick(a, again, first.movieId()).andExpect(status().isOk());
        assertThat(pendingMovieIds(a)).containsExactly(second.movieId());
        // ...but it is listed once (latest watch) for the member who did not dismiss it.
        assertThat(pendingMovieIds(b)).containsExactly(first.movieId(), second.movieId());
        pendingRatings(b).andExpect(jsonPath("$[0].sessionId").value(again.toString()));
    }

    @Test
    void dismissingAMovieNotInHistoryIsAllowedAndAnUnknownMovieIs404() throws Exception {
        TestUser a = user();
        dismissPending(a, newMovie()).andExpect(status().isNoContent());
        dismissPending(a, -7).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Movie not found."));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/me/pending-ratings/abc/dismiss").with(a.auth()))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions undismissPending(TestUser user, int movieId)
            throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/me/pending-ratings/{id}/dismiss", movieId).with(user.auth()));
    }

    @Test
    void undoingADismissalBringsThePromptBackIdempotently() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        Watched first = watchAdHoc(a, b);
        Watched second = watchAdHoc(a, b);
        dismissPending(a, first.movieId()).andExpect(status().isNoContent());
        assertThat(pendingMovieIds(a)).containsExactly(second.movieId());

        undismissPending(a, first.movieId()).andExpect(status().isNoContent());
        undismissPending(a, first.movieId()).andExpect(status().isNoContent());
        assertThat(rows("select count(*) from app.pending_rating_dismissals where user_id = ?", a.id())).isZero();
        assertThat(pendingMovieIds(a)).containsExactly(second.movieId(), first.movieId());
        // Nothing to undo (never dismissed, unknown movie) is fine too.
        undismissPending(a, second.movieId()).andExpect(status().isNoContent());
        undismissPending(a, -7).andExpect(status().isNoContent());
        undismissPending(a, 0).andExpect(status().isNoContent());
    }

    @Test
    void undoNeedsATokenAndOnlyTouchesTheCallersDismissal() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        Watched watched = watchAdHoc(a, b);
        dismissPending(a, watched.movieId()).andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/me/pending-ratings/{id}/dismiss", watched.movieId()))
                .andExpect(status().isUnauthorized());
        undismissPending(b, watched.movieId()).andExpect(status().isNoContent());
        assertThat(pendingMovieIds(a)).isEmpty();
        assertThat(rows("select count(*) from app.pending_rating_dismissals where user_id = ?", a.id())).isEqualTo(1);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/me/pending-ratings/abc/dismiss").with(a.auth()))
                .andExpect(status().isBadRequest());
    }
}
