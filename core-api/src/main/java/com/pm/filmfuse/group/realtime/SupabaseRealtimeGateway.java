package com.pm.filmfuse.group.realtime;

import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.user.gateway.SupabaseProperties;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code POST <SUPABASE_URL>/realtime/v1/api/broadcast} with the service-role key (as both
 * {@code apikey} and bearer token), body
 * {@code {"messages": [{"topic", "event", "payload", "private": true}]}}.
 */
public class SupabaseRealtimeGateway implements RealtimeGateway {

    static final String UNAVAILABLE = "Realtime is temporarily unavailable.";

    private final RestClient restClient;

    public SupabaseRealtimeGateway(RestClient.Builder builder, SupabaseProperties properties) {
        this.restClient = builder
                .baseUrl(properties.url() + "/realtime/v1")
                .defaultHeader("apikey", properties.serviceRoleKey())
                .defaultHeader("Authorization", "Bearer " + properties.serviceRoleKey())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new UpstreamUnavailableException(UNAVAILABLE, new IllegalStateException(
                            "Supabase Realtime broadcast returned " + response.getStatusCode().value()));
                })
                .build();
    }

    @Override
    public void broadcast(String topic, String event, Map<String, Object> payload) {
        Map<String, Object> message = Map.of("topic", topic, "event", event, "payload", payload, "private", true);
        try {
            restClient.post()
                    .uri("/api/broadcast")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messages", List.of(message)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new UpstreamUnavailableException(UNAVAILABLE, ex);
        }
    }
}
