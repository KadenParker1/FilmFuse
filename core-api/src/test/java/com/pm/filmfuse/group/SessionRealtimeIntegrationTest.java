package com.pm.filmfuse.group;

import com.pm.filmfuse.group.realtime.SessionUpdatedEvent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** session_updated broadcasts: after commit only, one per change, never failing the request. */
class SessionRealtimeIntegrationTest extends AbstractGroupIntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private void expectBroadcast(UUID sessionId, String type) {
        verify(realtimeGateway).broadcast("session:" + sessionId, "session_updated",
                Map.of("sessionId", sessionId.toString(), "type", type));
    }

    @Test
    void voteRefreshAndPickEachBroadcastOnce() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        verifyNoInteractions(realtimeGateway);

        vote(a, sessionId, movie, "UP").andExpect(status().isOk());
        expectBroadcast(sessionId, "vote");

        org.mockito.Mockito.clearInvocations(realtimeGateway);
        mockMvc.perform(delete("/api/v1/sessions/{id}/votes/{m}", sessionId, movie).with(a.auth()))
                .andExpect(status().isOk());
        expectBroadcast(sessionId, "vote");

        org.mockito.Mockito.clearInvocations(realtimeGateway);
        mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(b.auth())).andExpect(status().isOk());
        expectBroadcast(sessionId, "refresh");

        org.mockito.Mockito.clearInvocations(realtimeGateway);
        pick(b, sessionId, movie).andExpect(status().isOk());
        expectBroadcast(sessionId, "pick");
    }

    @Test
    void failedChangesBroadcastNothing() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));

        vote(a, sessionId, 123, "UP").andExpect(status().isNotFound());
        pick(a, sessionId, 123).andExpect(status().isBadRequest());
        pick(a, sessionId, movie).andExpect(status().isOk());
        org.mockito.Mockito.clearInvocations(realtimeGateway);
        vote(a, sessionId, movie, "UP").andExpect(status().isConflict());

        verify(realtimeGateway, never()).broadcast(anyString(), anyString(), anyMap());
    }

    @Test
    void aRolledBackTransactionBroadcastsNothing() {
        UUID sessionId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            events.publishEvent(new SessionUpdatedEvent(sessionId, SessionUpdatedEvent.VOTE));
            status.setRollbackOnly();
        });
        verify(realtimeGateway, never()).broadcast(anyString(), anyString(), anyMap());

        transactionTemplate.executeWithoutResult(status ->
                events.publishEvent(new SessionUpdatedEvent(sessionId, SessionUpdatedEvent.VOTE)));
        expectBroadcast(sessionId, "vote");
    }

    @Test
    void aBroadcastFailureDoesNotFailTheVote() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        doThrow(new IllegalStateException("realtime down")).when(realtimeGateway).broadcast(any(), any(), any());

        vote(a, sessionId, movie, "UP").andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].myVote").value("UP"));
        getSession(b, sessionId).andExpect(jsonPath("$.results[0].votes.up").value(1));
    }

    @Test
    void creatingASessionDoesNotBroadcast() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        mockMvc.perform(post("/api/v1/sessions").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\": [\"" + b.id() + "\"]}"))
                .andExpect(status().isCreated());
        verifyNoInteractions(realtimeGateway);
    }
}
