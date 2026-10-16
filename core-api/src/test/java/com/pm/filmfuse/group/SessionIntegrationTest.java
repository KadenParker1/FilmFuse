package com.pm.filmfuse.group;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.reco.service.FallbackRanker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The /sessions endpoints with the FallbackRanker (no ML service), and every session rule in the contract. */
class SessionIntegrationTest extends AbstractGroupIntegrationTest {

    // ------------------------------------------------------------------------------- create

    @Test
    void groupSessionSnapshotsActiveMembersAndRanksThePoolByPopularity() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        TestUser invitee = user();
        UUID groupId = activeGroup(owner, member);
        befriend(owner, invitee);
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + invitee.id() + "\"]}"));
        int genre = uniqueGenre();
        int low = movie(genre, "PG", 10);
        int high = movie(genre, "R", 30);
        int mid = movie(genre, "PG-13", 20);

        String body = json(createGroupSession(member, groupId, genre)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(groupId.toString()))
                .andExpect(jsonPath("$.groupName").value("Movie night"))
                .andExpect(jsonPath("$.createdBy.id").value(member.id().toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.strategy").value("MEAN"))
                .andExpect(jsonPath("$.filters.genreIds[0]").value(genre))
                .andExpect(jsonPath("$.filters.maxRuntime").value(nullValue()))
                .andExpect(jsonPath("$.effectiveFilter.label").value("No limits"))
                .andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.members[0].hasVector").value(false))
                .andExpect(jsonPath("$.members[0].voted").value(false))
                .andExpect(jsonPath("$.pickedMovie").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.results", hasSize(3)))
                .andExpect(jsonPath("$.results[0].rank").value(1))
                .andExpect(jsonPath("$.results[0].movie.title").value("Movie " + high))
                .andExpect(jsonPath("$.results[0].certification").value("R"))
                .andExpect(jsonPath("$.results[1].certification").value("PG_13"))
                .andExpect(jsonPath("$.results[0].groupScore").value(FallbackRanker.NEUTRAL_SCORE))
                .andExpect(jsonPath("$.results[0].memberScores['" + owner.id() + "']").value(FallbackRanker.NEUTRAL_SCORE))
                .andExpect(jsonPath("$.results[0].memberScores['" + member.id() + "']").value(FallbackRanker.NEUTRAL_SCORE))
                .andExpect(jsonPath("$.results[0].explanation").value(FallbackRanker.EXPLANATION))
                .andExpect(jsonPath("$.results[0].votes.up").value(0))
                .andExpect(jsonPath("$.results[0].votes.down").value(0))
                .andExpect(jsonPath("$.results[0].votes.vetoed").value(false))
                .andExpect(jsonPath("$.results[0].myVote").value(nullValue())));

        assertThat(resultIds(body)).containsExactly(high, mid, low);
        List<String> memberIds = JsonPath.read(body, "$.members[*].user.id");
        assertThat(memberIds).containsExactlyInAnyOrder(owner.id().toString(), member.id().toString());
    }

    @Test
    void sessionsReturnAtMostTwentyResults() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        befriend(me, friend);
        int genre = uniqueGenre();
        for (int i = 0; i < 25; i++) {
            movie(genre, "PG", i);
        }
        String body = json(createAdHocSession(me, genre, friend).andExpect(status().isCreated()));
        assertThat(resultIds(body)).hasSize(20);
        assertThat(JsonPath.<List<Integer>>read(body, "$.results[*].rank")).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
    }

    @Test
    void adHocSessionAddsTheCallerAndRequiresFriends() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        TestUser stranger = user();
        befriend(me, friend);
        int genre = uniqueGenre();
        movie(genre, "G", 1);

        createAdHocSession(me, genre, friend)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(nullValue()))
                .andExpect(jsonPath("$.groupName").value(nullValue()))
                .andExpect(jsonPath("$.members", hasSize(2)));

        createAdHocSession(me, genre, stranger)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can only add your friends to a group."));
        // Only yourself is not a session.
        createAdHocSession(me, genre, me)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose a group or at least one friend for the session."));
    }

    @Test
    void adHocSessionRefusesBlockedFriends() throws Exception {
        TestUser me = user();
        TestUser blocker = user();
        befriend(me, blocker);
        block(blocker, me);
        createAdHocSession(me, uniqueGenre(), blocker).andExpect(status().isBadRequest());
    }

    @Test
    void exactlyOneOfGroupOrMembers() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        UUID groupId = activeGroup(me, friend);
        mockMvc.perform(post("/api/v1/sessions").with(me.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\": \"" + groupId + "\", \"memberIds\": [\"" + friend.id() + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose either a group or friends for the session, not both."));
        mockMvc.perform(post("/api/v1/sessions").with(me.auth()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose a group or at least one friend for the session."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"memberIds\": []}",
            "{\"memberIds\": [null]}",
            "{\"memberIds\": [\"%s\"], \"filters\": {\"maxRuntime\": 39}}",
            "{\"memberIds\": [\"%s\"], \"filters\": {\"maxRuntime\": 401}}",
            "{\"memberIds\": [\"%s\"], \"filters\": {\"minYear\": 1899}}",
            "{\"memberIds\": [\"%s\"], \"filters\": {\"minYear\": 2101}}",
            "{\"memberIds\": [\"%s\"], \"strategy\": \"RANDOM\"}",
            "{\"memberIds\": [\"%s\"], \"strategy\": \"mean\"}",
            "{\"groupId\": \"not-a-uuid\"}"
    })
    void createValidatesTheBody(String template) throws Exception {
        TestUser me = user();
        TestUser friend = user();
        befriend(me, friend);
        mockMvc.perform(post("/api/v1/sessions").with(me.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted(friend.id())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAcceptsEveryStrategyAndKeepsIt() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        befriend(me, friend);
        for (String strategy : List.of("MEAN", "LEAST_MISERY", "MOST_PLEASURE", "CLOSENESS_WEIGHTED", "FAIRNESS")) {
            mockMvc.perform(post("/api/v1/sessions").with(me.auth()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"memberIds\": [\"" + friend.id() + "\"], \"strategy\": \"" + strategy + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.strategy").value(strategy));
        }
    }

    @Test
    void groupSessionNeedsAnActiveMembership() throws Exception {
        TestUser owner = user();
        TestUser invitee = user();
        befriend(owner, invitee);
        UUID groupId = JsonPathId.of(json(createGroup(owner, "G", invitee).andExpect(status().isCreated())));
        int genre = uniqueGenre();

        createGroupSession(invitee, groupId, genre)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Accept the group invite first."));
        createGroupSession(user(), groupId, genre).andExpect(status().isNotFound());
        createGroupSession(owner, UUID.randomUUID(), genre).andExpect(status().isNotFound());
    }

    @Test
    void sessionFiltersRestrictThePool() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        befriend(me, friend);
        int genre = uniqueGenre();
        int otherGenre = uniqueGenre();
        int keep = movie(genre, "PG", 5);
        int tooLong = movie(genre, "PG", 9);
        int tooOld = movie(genre, "PG", 8);
        int unknownRuntime = movie(genre, "PG", 7);
        int wrongGenre = movie(otherGenre, "PG", 99);
        setRuntimeAndYear(keep, 90, 2010);
        setRuntimeAndYear(tooLong, 181, 2010);
        setRuntimeAndYear(tooOld, 90, 1979);
        setRuntimeAndYear(unknownRuntime, null, 2015);

        String body = json(mockMvc.perform(post("/api/v1/sessions").with(me.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\": [\"" + friend.id() + "\"], \"filters\": {\"maxRuntime\": 180, "
                                + "\"minYear\": 1980, \"genreIds\": [" + genre + "]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filters.maxRuntime").value(180))
                .andExpect(jsonPath("$.filters.minYear").value(1980)));

        assertThat(resultIds(body)).containsExactly(unknownRuntime, keep).doesNotContain(tooLong, tooOld, wrongGenre);
    }

    @Test
    void anEmptyPoolIsASessionWithoutResults() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        befriend(me, friend);
        createAdHocSession(me, uniqueGenre(), friend)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.results", hasSize(0)));
    }

    // ------------------------------------------------------------------------------- access

    @Test
    void onlyMembersCanSeeASession() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        TestUser outsider = user();
        befriend(me, friend);
        befriend(me, outsider);
        UUID sessionId = sessionId(createAdHocSession(me, uniqueGenre(), friend));

        getSession(friend, sessionId).andExpect(status().isOk());
        getSession(outsider, sessionId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Session not found."));
        getSession(me, UUID.randomUUID()).andExpect(status().isNotFound());
        vote(outsider, sessionId, 1, "UP").andExpect(status().isNotFound());
        pick(outsider, sessionId, 1).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(outsider.auth()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/sessions/{id}/votes/{m}", sessionId, 1).with(outsider.auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void laterGroupMembersDoNotSeeEarlierSessions() throws Exception {
        TestUser owner = user();
        TestUser early = user();
        TestUser late = user();
        UUID groupId = activeGroup(owner, early);
        UUID sessionId = sessionId(createGroupSession(owner, groupId, uniqueGenre()));
        befriend(owner, late);
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + late.id() + "\"]}"));
        acceptGroupInvite(late, groupId).andExpect(status().isOk());

        getSession(late, sessionId).andExpect(status().isNotFound());
        getGroup(late, groupId).andExpect(jsonPath("$.recentSessions", hasSize(0)));
        getGroup(early, groupId).andExpect(jsonPath("$.recentSessions[0].id").value(sessionId.toString()));
    }

    // ------------------------------------------------------------------------------- votes

    @Test
    void votesAreTalliedPerMovieWithTheCallersOwnVote() throws Exception {
        TestUser a = user();
        TestUser b = user();
        TestUser c = user();
        befriend(a, b);
        befriend(a, c);
        int genre = uniqueGenre();
        int first = movie(genre, "PG", 3);
        int second = movie(genre, "PG", 2);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b, c));

        vote(a, sessionId, first, "UP").andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].myVote").value("UP"))
                .andExpect(jsonPath("$.results[0].votes.up").value(1));
        vote(b, sessionId, first, "UP").andExpect(status().isOk());
        vote(c, sessionId, first, "DOWN").andExpect(status().isOk());
        // Changing your mind replaces the vote.
        vote(a, sessionId, second, "DOWN").andExpect(status().isOk());
        vote(a, sessionId, second, "UP").andExpect(status().isOk());

        getSession(b, sessionId)
                .andExpect(jsonPath("$.results[0].movie.id").value(first))
                .andExpect(jsonPath("$.results[0].votes.up").value(2))
                .andExpect(jsonPath("$.results[0].votes.down").value(1))
                .andExpect(jsonPath("$.results[0].votes.vetoed").value(false))
                .andExpect(jsonPath("$.results[0].myVote").value("UP"))
                .andExpect(jsonPath("$.results[1].votes.up").value(1))
                .andExpect(jsonPath("$.results[1].votes.down").value(0))
                .andExpect(jsonPath("$.results[1].myVote").value(nullValue()))
                .andExpect(jsonPath("$.members[?(@.user.id == '" + c.id() + "')].voted").value(true));
    }

    @Test
    void vetoedResultsAreListedLast() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int first = movie(genre, "PG", 3);
        int second = movie(genre, "PG", 2);
        int third = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));

        String body = json(vote(b, sessionId, first, "VETO").andExpect(status().isOk())
                .andExpect(jsonPath("$.results[2].votes.vetoed").value(true))
                .andExpect(jsonPath("$.results[2].rank").value(1)));
        assertThat(resultIds(body)).containsExactly(second, third, first);
    }

    @Test
    void clearingAVoteRemovesIt() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        vote(a, sessionId, movie, "VETO").andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/sessions/{id}/votes/{m}", sessionId, movie).with(a.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].votes.vetoed").value(false))
                .andExpect(jsonPath("$.results[0].myVote").value(nullValue()))
                .andExpect(jsonPath("$.members[?(@.user.id == '" + a.id() + "')].voted").value(false));
        // Idempotent.
        mockMvc.perform(delete("/api/v1/sessions/{id}/votes/{m}", sessionId, movie).with(a.auth()))
                .andExpect(status().isOk());
    }

    @Test
    void votingOnAMovieThatIsNotAResultIs404() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        movie(genre, "PG", 1);
        int notShown = movie(uniqueGenre(), "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));

        vote(a, sessionId, notShown, "UP").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("That movie is not in this session's results."));
        mockMvc.perform(delete("/api/v1/sessions/{id}/votes/{m}", sessionId, notShown).with(a.auth()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"vote\": null}", "{\"vote\": \"MAYBE\"}", "{\"vote\": \"up\"}"})
    void voteValidatesTheBody(String body) throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/sessions/{id}/votes/{m}", sessionId, movie).with(a.auth())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------- refresh

    @Test
    void refreshAppendsNewResultsAndNeverRepeatsShownOrVetoedOnes() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        List<Integer> movies = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            movies.add(movie(genre, "PG", 100 - i));
        }
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        vote(b, sessionId, movies.get(0), "VETO").andExpect(status().isOk());

        String body = json(mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(b.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(25))));

        List<Integer> ranks = JsonPath.read(body, "$.results[*].rank");
        // 24 un-vetoed in rank order 2..25, then the vetoed rank 1.
        assertThat(ranks.subList(0, 24)).isSorted();
        assertThat(ranks.getLast()).isEqualTo(1);
        assertThat(resultIds(body)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(movies);
        assertThat(resultIds(body).subList(19, 24)).containsExactlyElementsOf(movies.subList(20, 25));

        // Nothing new left: refresh changes nothing.
        String again = json(mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(a.auth()))
                .andExpect(status().isOk()));
        assertThat(resultIds(again)).isEqualTo(resultIds(body));
    }

    @Test
    void refreshAppliesComfortChangedSinceCreation() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        for (int i = 0; i < 20; i++) {
            movie(genre, "PG", 100 - i);
        }
        int rated = movie(genre, "R", 1);
        int pg = movie(genre, "PG", 0.5);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        setComfort(b, "{\"maxCertification\": \"PG\", \"contentLimits\": {}, \"allowUnrated\": false}");

        String body = json(mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(a.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveFilter.maxCertification").value("PG")));
        assertThat(resultIds(body)).contains(pg).doesNotContain(rated);
    }

    // ------------------------------------------------------------------------------- pick

    @Test
    void anyMemberPicksAndEveryMemberGetsWatchHistory() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));

        pick(b, sessionId, movie)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.pickedMovie.id").value(movie));

        assertThat(jdbc.queryForList("select user_id from app.watch_history where session_id = ? and movie_id = ?",
                UUID.class, sessionId, movie)).containsExactlyInAnyOrder(a.id(), b.id());
        getSession(a, sessionId).andExpect(jsonPath("$.status").value("DECIDED"));

        // Decided: everything that changes the session is a 409.
        pick(a, sessionId, movie).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This session is no longer open."));
        vote(a, sessionId, movie, "UP").andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/sessions/{id}/votes/{m}", sessionId, movie).with(a.auth()))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(a.auth()))
                .andExpect(status().isConflict());
    }

    @Test
    void pickingAVetoedMovieOrANonResultIs400() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int vetoed = movie(genre, "PG", 2);
        movie(genre, "PG", 1);
        int notShown = movie(uniqueGenre(), "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        vote(b, sessionId, vetoed, "VETO").andExpect(status().isOk());

        pick(a, sessionId, vetoed).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That movie was vetoed."));
        pick(a, sessionId, notShown).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That movie is not in this session's results."));
        mockMvc.perform(post("/api/v1/sessions/{id}/pick", sessionId).with(a.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        getSession(a, sessionId).andExpect(jsonPath("$.status").value("OPEN"));
    }

    // ------------------------------------------------------------------------------- expiry

    @Test
    void openSessionsExpireAfterTwentyFourHours() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));

        CLOCK.advance(Duration.ofHours(23));
        getSession(a, sessionId).andExpect(jsonPath("$.status").value("OPEN"));
        CLOCK.advance(Duration.ofHours(2));
        getSession(a, sessionId).andExpect(jsonPath("$.status").value("EXPIRED"));
        mockMvc.perform(get("/api/v1/sessions").with(a.auth()))
                .andExpect(jsonPath("$.items[0].status").value("EXPIRED"));

        vote(a, sessionId, movie, "UP").andExpect(status().isConflict());
        pick(a, sessionId, movie).andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(a.auth()))
                .andExpect(status().isConflict());
    }

    @Test
    void decidedSessionsNeverExpire() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        pick(a, sessionId, movie).andExpect(status().isOk());
        CLOCK.advance(Duration.ofDays(3));
        getSession(a, sessionId).andExpect(jsonPath("$.status").value("DECIDED"));
    }

    // ------------------------------------------------------------------------------- history

    @Test
    void historyIsNewestFirstPaginatedAndFilterableByGroup() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        UUID groupId = activeGroup(me, friend);
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            created.add(sessionId(createAdHocSession(me, uniqueGenre(), friend)));
            CLOCK.advance(Duration.ofSeconds(1));
        }
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID groupSession = sessionId(createGroupSession(friend, groupId, genre));
        pick(me, groupSession, movie).andExpect(status().isOk());

        String first = json(mockMvc.perform(get("/api/v1/sessions").with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.items[0].id").value(groupSession.toString()))
                .andExpect(jsonPath("$.items[0].groupId").value(groupId.toString()))
                .andExpect(jsonPath("$.items[0].groupName").value("Movie night"))
                .andExpect(jsonPath("$.items[0].status").value("DECIDED"))
                .andExpect(jsonPath("$.items[0].pickedMovie.id").value(movie))
                .andExpect(jsonPath("$.items[0].memberCount").value(2))
                .andExpect(jsonPath("$.items[1].id").value(created.get(20).toString()))
                .andExpect(jsonPath("$.items[1].pickedMovie").value(nullValue()))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty()));
        String cursor = JsonPath.read(first, "$.nextCursor");

        mockMvc.perform(get("/api/v1/sessions").param("cursor", cursor).with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[1].id").value(created.get(0).toString()))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        mockMvc.perform(get("/api/v1/sessions").param("groupId", groupId.toString()).with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(groupSession.toString()));

        getGroup(me, groupId)
                .andExpect(jsonPath("$.recentSessions", hasSize(1)))
                .andExpect(jsonPath("$.recentSessions[0].pickedMovie.id").value(movie));
        mockMvc.perform(get("/api/v1/groups").with(me.auth()))
                .andExpect(jsonPath("$[0].id").value(groupId.toString()))
                .andExpect(jsonPath("$[0].lastSessionAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/sessions").with(user().auth()))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage!", "bm9wZQ", "MjAyNi0wMS0wMVQwMDowMDowMFofbm90LWEtdXVpZA", "eWVzdGVyZGF5Hzc2ZTdhYmIxLTkyMGUtNGY0Ni05MThiLTJiZWU4ZWQyNTcxZA"})
    void invalidHistoryCursorIs400(String cursor) throws Exception {
        mockMvc.perform(get("/api/v1/sessions").param("cursor", cursor).with(user().auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor."));
    }

    // ------------------------------------------------------------------------------- deletion

    @Test
    void sessionsSurviveTheirCreatorsAccountDeletion() throws Exception {
        TestUser creator = user();
        TestUser friend = user();
        befriend(creator, friend);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(creator, genre, friend));
        vote(creator, sessionId, movie, "VETO").andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/me").with(creator.auth())).andExpect(status().isNoContent());

        getSession(friend, sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value(nullValue()))
                .andExpect(jsonPath("$.members", hasSize(1)))
                .andExpect(jsonPath("$.results[0].votes.vetoed").value(false));
    }

    /** Reads {@code $.id}. */
    private static final class JsonPathId {
        static UUID of(String body) {
            return UUID.fromString(JsonPath.read(body, "$.id"));
        }
    }
}
