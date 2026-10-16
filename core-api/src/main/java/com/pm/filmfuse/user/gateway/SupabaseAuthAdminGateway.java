package com.pm.filmfuse.user.gateway;

import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** {@code DELETE <SUPABASE_URL>/auth/v1/admin/users/{id}} with the service-role key. */
public class SupabaseAuthAdminGateway implements AuthAdminGateway {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAuthAdminGateway.class);

    static final String UNAVAILABLE = "Account service is temporarily unavailable.";

    private final RestClient restClient;

    public SupabaseAuthAdminGateway(RestClient.Builder builder, SupabaseProperties properties) {
        this.restClient = builder
                .baseUrl(properties.url() + "/auth/v1")
                .defaultHeader("Authorization", "Bearer " + properties.serviceRoleKey())
                .defaultHeader("apikey", properties.serviceRoleKey())
                .build();
    }

    @Override
    public void deleteUser(UUID userId) {
        try {
            restClient.delete()
                    .uri("/admin/users/{id}", userId)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) ->
                            log.info("Auth user {} was already deleted", userId))
                    .onStatus(status -> status.isError() && status.value() != HttpStatus.NOT_FOUND.value(),
                            (request, response) -> {
                                throw new UpstreamUnavailableException(UNAVAILABLE, new IllegalStateException(
                                        "Supabase Auth admin delete returned " + response.getStatusCode().value()));
                            })
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new UpstreamUnavailableException(UNAVAILABLE, ex);
        }
    }
}
