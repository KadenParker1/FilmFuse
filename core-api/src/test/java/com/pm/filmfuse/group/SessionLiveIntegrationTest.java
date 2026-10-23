package com.pm.filmfuse.group;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.email.EmailMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The session hub (GET /sessions?status=, summaries), late joiners (POST /sessions/{id}/members,
 * POST /sessions/{id}/join), ending early (POST /sessions/{id}/end) and the share link.
 */
class SessionLiveIntegrationTest extends AbstractGroupIntegrationTest {

    private static final String WEB = "http://localhost:8081";
    private static final String FRIENDS_ONLY = "You can only add your friends to a group.";

    private ResultActions addMembers(TestUser caller, UUID sessionId, TestUser... users) throws Exception {
        String ids = Arrays.stream(users).map(u -> "\"" + u.id() + "\"").collect(Collectors.joining(","));
        return mockMvc.perform(post("/api/v1/sessions/{id}/members", sessionId).with(caller.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [" + ids + "]}"));
    }

    private ResultActions join(TestUser caller, UUID sessionId) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{id}/join", sessionId).with(caller.auth()));
    }

    private ResultActions end(TestUser caller, UUID sessionId) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{id}/end", sessionId).with(caller.auth()));
    }

    private ResultActions list(TestUser caller, String status) throws Exception {
        return mockMvc.perform(get("/api/v1/sessions").param("status", status).with(caller.auth()));
    }

    private void expectBroadcast(UUID sessionId, String type) {
        verify(realtimeGateway, atLeast(1)).broadcast("session:" + sessionId, "session_updated",
                Map.of("sessionId", sessionId.toString(), "type", type));
    }

    private List<EmailMessage> emailsTo(TestUser user) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailGateway, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().filter(m -> m.to().equals(user.email())).toList();
    }

    private void addGroupMember(TestUser owner, UUID groupId, TestUser user) throws Exception {
        befriend(owner, user);
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + user.id() + "\"]}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------- hub lists

    @Test
    void liveNowAndHistoryAreSplitByStatusWithCountsAndPreview() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        befriend(me, friend);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID open = sessionId(createAdHocSession(me, genre, friend));
        UUID decided = sessionId(createAdHocSession(me, genre, friend));
        pick(me, decided, movie).andExpect(status().isOk());
        UUID ended = sessionId(createAdHocSession(me, genre, friend));
        end(me, ended).andExpect(status().isOk());
        vote(friend, open, movie, "UP").andExpect(status().isOk());

        list(me, "OPEN").andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(open.toString()))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].memberCount").value(2))
                .andExpect(jsonPath("$.items[0].votedCount").value(1))
                .andExpect(jsonPath("$.items[0].votedByMe").value(false))
                .andExpect(jsonPath("$.items[0].endedEarly").value(false))
                .andExpect(jsonPath("$.items[0].closesAt").isNotEmpty())
                // Other people first, the viewer last.
                .andExpect(jsonPath("$.items[0].memberPreview[0].id").value(friend.id().toString()))
                .andExpect(jsonPath("$.items[0].memberPreview[1].id").value(me.id().toString()));
        list(friend, "OPEN").andExpect(jsonPath("$.items[0].votedByMe").value(true));

        String history = json(list(me, "CLOSED").andExpect(status().isOk()));
        List<String> ids = JsonPath.read(history, "$.items[*].id");
        assertThat(ids).containsExactly(ended.toString(), decided.toString());
        assertThat((Boolean) JsonPath.read(history, "$.items[0].endedEarly")).isTrue();
        assertThat((String) JsonPath.read(history, "$.items[0].status")).isEqualTo("EXPIRED");
        assertThat((Integer) JsonPath.read(history, "$.items[1].pickedMovie.id")).isEqualTo(movie);

        // Time running out moves an OPEN session into the history (not ended early).
        CLOCK.advance(Duration.ofHours(25));
        list(me, "OPEN").andExpect(jsonPath("$.items", hasSize(0)));
        String later = json(list(me, "CLOSED"));
        assertThat((List<String>) JsonPath.read(later, "$.items[*].id")).hasSize(3).contains(open.toString());
        mockMvc.perform(get("/api/v1/sessions").with(me.auth())).andExpect(jsonPath("$.items", hasSize(3)));
    }

    @Test
    void previewsLeaveOutBlockedPeopleButCountThem() throws Exception {
        TestUser owner = user();
        TestUser a = user();
        TestUser b = user();
        UUID groupId = activeGroup(owner, a, b);
        UUID sessionId = sessionId(createGroupSession(owner, groupId, uniqueGenre()));
        block(a, b);

        String body = json(list(a, "OPEN"));
        List<String> preview = JsonPath.read(body, "$.items[0].memberPreview[*].id");
        assertThat(preview).containsExactly(owner.id().toString(), a.id().toString());
        assertThat((Integer) JsonPath.read(body, "$.items[0].memberCount")).isEqualTo(3);
        assertThat((String) JsonPath.read(body, "$.items[0].id")).isEqualTo(sessionId.toString());
    }

    @Test
    void historyPagesWithALimit() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        befriend(me, friend);
        int genre = uniqueGenre();
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID id = sessionId(createAdHocSession(me, genre, friend));
            end(me, id).andExpect(status().isOk());
            created.add(id);
            CLOCK.advance(Duration.ofSeconds(1));
        }
        String first = json(mockMvc.perform(get("/api/v1/sessions").param("status", "CLOSED").param("limit", "2")
                .with(me.auth())).andExpect(jsonPath("$.items", hasSize(2))));
        String cursor = JsonPath.read(first, "$.nextCursor");
        mockMvc.perform(get("/api/v1/sessions").param("status", "CLOSED").param("limit", "2").param("cursor", cursor)
                        .with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(created.getFirst().toString()))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=51", "status=SOMETIMES"})
    void badListParametersAre400(String param) throws Exception {
        String[] kv = param.split("=");
        mockMvc.perform(get("/api/v1/sessions").param(kv[0], kv[1]).with(user().auth()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------- add members

    @Test
    void aMemberAddsAFriendWhoIsEmailedBroadcastAndCanVote() throws Exception {
        TestUser host = user();
        TestUser guest = user();
        TestUser late = user();
        befriend(host, guest);
        befriend(guest, late);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(host, genre, guest));
        Mockito.clearInvocations(realtimeGateway, emailGateway);

        // Any member - not just the creator - adds their own friend.
        addMembers(guest, sessionId, late).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3));
        expectBroadcast(sessionId, "members");
        assertThat(emailsTo(late)).singleElement().satisfies(m -> {
            assertThat(m.subject()).endsWith("added you to a movie session");
            assertThat(m.body()).contains(WEB + "/sessions/" + sessionId);
        });
        assertThat(emailsTo(host)).isEmpty();

        vote(late, sessionId, movie, "UP").andExpect(status().isOk());
        getSession(host, sessionId).andExpect(jsonPath("$.votedCount").value(1))
                .andExpect(jsonPath("$.results[0].votes.up").value(1));
        list(late, "OPEN").andExpect(jsonPath("$.items[0].id").value(sessionId.toString()));

        // Adding someone already in it changes nothing and broadcasts nothing.
        Mockito.clearInvocations(realtimeGateway);
        addMembers(host, sessionId, guest).andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(3));
        verify(realtimeGateway, never()).broadcast(anyString(), anyString(), anyMap());
    }

    @Test
    void onlyMembersAddOnlyTheirOwnUnblockedFriendsToOpenSessions() throws Exception {
        TestUser host = user();
        TestUser guest = user();
        TestUser outsider = user();
        TestUser stranger = user();
        TestUser blocked = user();
        befriend(host, guest);
        befriend(outsider, host);
        befriend(host, blocked);
        UUID sessionId = sessionId(createAdHocSession(host, uniqueGenre(), guest));

        addMembers(outsider, sessionId, outsider).andExpect(status().isNotFound());
        addMembers(host, sessionId, stranger).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(FRIENDS_ONLY));
        block(blocked, host);
        addMembers(host, sessionId, blocked).andExpect(status().isBadRequest());
        // A friend of the host is not the guest's friend.
        addMembers(guest, sessionId, outsider).andExpect(status().isBadRequest());
        getSession(host, sessionId).andExpect(jsonPath("$.memberCount").value(2));

        end(host, sessionId).andExpect(status().isOk());
        addMembers(host, sessionId, outsider).andExpect(status().isConflict());
    }

    @Test
    void addMembersValidatesTheBody() throws Exception {
        TestUser host = user();
        TestUser guest = user();
        befriend(host, guest);
        UUID sessionId = sessionId(createAdHocSession(host, uniqueGenre(), guest));
        mockMvc.perform(post("/api/v1/sessions/{id}/members", sessionId).with(host.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": []}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSessionHoldsAtMostTwentyPeople() throws Exception {
        TestUser host = user();
        List<TestUser> friends = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TestUser friend = user();
            befriend(host, friend);
            friends.add(friend);
        }
        UUID sessionId = sessionId(createAdHocSession(host, uniqueGenre(), friends.subList(0, 18).toArray(TestUser[]::new)));
        addMembers(host, sessionId, friends.get(18)).andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(20));
        addMembers(host, sessionId, friends.get(19)).andExpect(status().isConflict());
    }

    @Test
    void aStricterLateJoinerHidesIneligibleResultsForEveryoneWithoutAttribution() throws Exception {
        TestUser host = user();
        TestUser guest = user();
        TestUser strict = user();
        befriend(host, guest);
        befriend(host, strict);
        int genre = uniqueGenre();
        int rated = movie(genre, "R", 20);
        int pg = movie(genre, "PG", 10);
        setComfort(strict, "{\"maxCertification\": \"PG_13\", \"allowUnrated\": true}");
        UUID sessionId = sessionId(createAdHocSession(host, genre, guest));
        vote(guest, sessionId, rated, "UP").andExpect(status().isOk());
        assertThat(resultIds(json(getSession(guest, sessionId)))).containsExactly(rated, pg);

        String added = json(addMembers(host, sessionId, strict).andExpect(status().isOk()));
        assertThat(resultIds(added)).containsExactly(pg);

        String seenByGuest = json(getSession(guest, sessionId)
                .andExpect(jsonPath("$.hiddenResultCount").value(1))
                .andExpect(jsonPath("$.effectiveFilter.maxCertification").value("PG_13")));
        assertThat(resultIds(seenByGuest)).containsExactly(pg);
        // The response names nobody as the cause: the only per-person data is the member list and fit scores.
        assertThat(seenByGuest).doesNotContain("PG_13\",\"by").doesNotContain("causedBy");
        pick(guest, sessionId, rated).andExpect(status().isBadRequest());

        // The stored effective filter follows too, and a refresh never brings the hidden one back.
        mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(host.auth())).andExpect(status().isOk());
        assertThat(resultIds(json(getSession(strict, sessionId)))).containsExactly(pg);
        String stored = jdbc.queryForObject("select effective_filter::text from app.rec_sessions where id = ?",
                String.class, sessionId);
        assertThat(stored).contains("PG-13");
    }

    // ------------------------------------------------------------------------------- join

    @Test
    void aNewGroupMemberJoinsAnOpenGroupSession() throws Exception {
        TestUser owner = user();
        TestUser early = user();
        TestUser late = user();
        UUID groupId = activeGroup(owner, early);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createGroupSession(owner, groupId, genre));
        addGroupMember(owner, groupId, late);

        // Invited only: nothing to see or join yet.
        getSession(late, sessionId).andExpect(status().isNotFound());
        join(late, sessionId).andExpect(status().isNotFound());
        getGroup(late, groupId).andExpect(jsonPath("$.joinableSessions", hasSize(0)));

        acceptGroupInvite(late, groupId).andExpect(status().isOk());
        getSession(late, sessionId).andExpect(status().isForbidden());
        getGroup(late, groupId).andExpect(jsonPath("$.joinableSessions[0].id").value(sessionId.toString()))
                .andExpect(jsonPath("$.joinableSessions[0].votedByMe").value(false));
        Mockito.clearInvocations(realtimeGateway, emailGateway);

        join(late, sessionId).andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(3));
        expectBroadcast(sessionId, "members");
        assertThat(emailsTo(late)).isEmpty();
        join(late, sessionId).andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(3));
        getGroup(late, groupId).andExpect(jsonPath("$.joinableSessions", hasSize(0)))
                .andExpect(jsonPath("$.recentSessions[0].id").value(sessionId.toString()));
        vote(late, sessionId, movie, "UP").andExpect(status().isOk());
        getSession(early, sessionId).andExpect(jsonPath("$.votedCount").value(1));
    }

    @Test
    void onlyActiveGroupMembersJoinAndOnlyOpenGroupSessions() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        TestUser friend = user();
        TestUser outsider = user();
        UUID groupId = activeGroup(owner, member);
        befriend(owner, friend);
        UUID groupSession = sessionId(createGroupSession(owner, groupId, uniqueGenre()));
        UUID adHoc = sessionId(createAdHocSession(owner, uniqueGenre(), friend));

        join(outsider, groupSession).andExpect(status().isNotFound());
        join(member, adHoc).andExpect(status().isNotFound());
        join(outsider, UUID.randomUUID()).andExpect(status().isNotFound());

        TestUser late = user();
        addGroupMember(owner, groupId, late);
        acceptGroupInvite(late, groupId).andExpect(status().isOk());
        end(owner, groupSession).andExpect(status().isOk());
        join(late, groupSession).andExpect(status().isConflict());
        // A closed session is not offered, and shows as not found to a non-member.
        getSession(late, groupSession).andExpect(status().isNotFound());
        getGroup(late, groupId).andExpect(jsonPath("$.joinableSessions", hasSize(0)));
    }

    // ------------------------------------------------------------------------------- end

    @Test
    void theCreatorEndsASessionEarlyForEveryone() throws Exception {
        TestUser host = user();
        TestUser guest = user();
        TestUser outsider = user();
        befriend(host, guest);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(host, genre, guest));

        end(outsider, sessionId).andExpect(status().isNotFound());
        end(guest, sessionId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only the person who started this session can end it."));
        Mockito.clearInvocations(realtimeGateway);

        end(host, sessionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.endedEarly").value(true))
                .andExpect(jsonPath("$.pickedMovie").value(nullValue()));
        expectBroadcast(sessionId, "ended");

        getSession(guest, sessionId).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.endedEarly").value(true));
        end(host, sessionId).andExpect(status().isConflict());
        vote(guest, sessionId, movie, "UP").andExpect(status().isConflict());
        pick(guest, sessionId, movie).andExpect(status().isConflict());
        list(guest, "OPEN").andExpect(jsonPath("$.items", hasSize(0)));
        Integer watched = jdbc.queryForObject("select count(*) from app.watch_history where session_id = ?",
                Integer.class, sessionId);
        assertThat(watched).isZero();
    }

    @Test
    void decidedSessionsCannotBeEnded() throws Exception {
        TestUser host = user();
        TestUser guest = user();
        befriend(host, guest);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(host, genre, guest));
        pick(guest, sessionId, movie).andExpect(status().isOk());
        end(host, sessionId).andExpect(status().isConflict());
        getSession(host, sessionId).andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.endedEarly").value(false));
    }

    // ------------------------------------------------------------------------------- share link

    @Test
    void sessionsCarryTheirWebShareLinkAndCloseTime() throws Exception {
        TestUser host = user();
        TestUser guest = user();
        befriend(host, guest);
        UUID sessionId = sessionId(createAdHocSession(host, uniqueGenre(), guest));
        String body = json(getSession(guest, sessionId)
                .andExpect(jsonPath("$.shareUrl").value(WEB + "/sessions/" + sessionId))
                .andExpect(jsonPath("$.hiddenResultCount").value(0)));
        String createdAt = JsonPath.read(body, "$.createdAt");
        String closesAt = JsonPath.read(body, "$.closesAt");
        assertThat(java.time.Instant.parse(closesAt))
                .isEqualTo(java.time.Instant.parse(createdAt).plus(Duration.ofHours(24)));
    }
}
