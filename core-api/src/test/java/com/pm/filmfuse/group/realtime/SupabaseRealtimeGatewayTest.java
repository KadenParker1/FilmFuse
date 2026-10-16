package com.pm.filmfuse.group.realtime;

import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.user.gateway.SupabaseProperties;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class SupabaseRealtimeGatewayTest {

    private static final SupabaseProperties PROPERTIES = new SupabaseProperties("https://proj.supabase.co/", "service-key");

    private MockRestServiceServer server;
    private SupabaseRealtimeGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new SupabaseRealtimeGateway(builder, PROPERTIES);
    }

    @Test
    void postsAPrivateBroadcastWithTheServiceRoleKey() {
        UUID sessionId = UUID.fromString("0b4b8f2e-6f4c-4d39-9f43-6f0e7c2a9a11");
        server.expect(requestTo("https://proj.supabase.co/realtime/v1/api/broadcast"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", "service-key"))
                .andExpect(header("Authorization", "Bearer service-key"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("""
                        {"messages": [{
                          "topic": "session:0b4b8f2e-6f4c-4d39-9f43-6f0e7c2a9a11",
                          "event": "session_updated",
                          "payload": {"sessionId": "0b4b8f2e-6f4c-4d39-9f43-6f0e7c2a9a11", "type": "vote"},
                          "private": true
                        }]}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED));

        new SessionRealtimeNotifier(gateway).onSessionUpdated(new SessionUpdatedEvent(sessionId, "vote"));

        server.verify();
    }

    @Test
    void errorStatusesAndTimeoutsThrow() {
        server.expect(requestTo("https://proj.supabase.co/realtime/v1/api/broadcast")).andRespond(withServerError());
        assertThatThrownBy(() -> gateway.broadcast("session:x", "session_updated", Map.of()))
                .isInstanceOf(UpstreamUnavailableException.class);

        server.reset();
        server.expect(requestTo("https://proj.supabase.co/realtime/v1/api/broadcast"))
                .andRespond(withException(new IOException("timeout")));
        assertThatThrownBy(() -> gateway.broadcast("session:x", "session_updated", Map.of()))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void theNotifierSwallowsGatewayFailures() {
        RealtimeGateway failing = mock(RealtimeGateway.class);
        org.mockito.Mockito.doThrow(new UpstreamUnavailableException("down")).when(failing)
                .broadcast(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        UUID id = UUID.randomUUID();

        new SessionRealtimeNotifier(failing).onSessionUpdated(new SessionUpdatedEvent(id, "pick"));

        verify(failing).broadcast("session:" + id, "session_updated", Map.of("sessionId", id.toString(), "type", "pick"));
    }

    @Test
    void configPicksTheMockWithoutAServiceRoleKey() {
        RealtimeConfig config = new RealtimeConfig();
        assertThat(config.realtimeGateway(new SupabaseProperties("http://localhost:54321", "")))
                .isInstanceOf(MockRealtimeGateway.class);
        assertThat(config.realtimeGateway(new SupabaseProperties("http://localhost:54321", null)))
                .isInstanceOf(MockRealtimeGateway.class);
        assertThat(config.realtimeGateway(PROPERTIES)).isInstanceOf(SupabaseRealtimeGateway.class);
    }

    @Test
    void theMockRecordsBroadcasts() {
        MockRealtimeGateway mockGateway = new MockRealtimeGateway();
        mockGateway.broadcast("session:1", "session_updated", Map.of("type", "vote"));
        assertThat(mockGateway.sent()).containsExactly(
                new MockRealtimeGateway.Broadcast("session:1", "session_updated", Map.of("type", "vote")));
    }
}
