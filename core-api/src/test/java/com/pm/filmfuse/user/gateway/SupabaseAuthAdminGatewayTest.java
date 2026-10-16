package com.pm.filmfuse.user.gateway;

import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseAuthAdminGatewayTest {

    private static final String SUPABASE = "https://proj.supabase.test";
    private static final UUID USER = UUID.fromString("6f1c1d0e-2a8b-4c3d-9e4f-5a6b7c8d9e0f");

    private MockRestServiceServer server;
    private SupabaseAuthAdminGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new SupabaseAuthAdminGateway(builder, new SupabaseProperties(SUPABASE, "service-key"));
    }

    @Test
    void deletesTheUserWithTheServiceKey() {
        server.expect(requestTo(SUPABASE + "/auth/v1/admin/users/" + USER))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer service-key"))
                .andExpect(header("apikey", "service-key"))
                .andRespond(withSuccess());

        gateway.deleteUser(USER);

        server.verify();
    }

    @Test
    void alreadyDeletedUserIsNotAnError() {
        server.expect(requestTo(SUPABASE + "/auth/v1/admin/users/" + USER))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatCode(() -> gateway.deleteUser(USER)).doesNotThrowAnyException();
    }

    @Test
    void serverErrorIsUpstreamUnavailable() {
        server.expect(requestTo(SUPABASE + "/auth/v1/admin/users/" + USER))
                .andRespond(withServerError());

        assertThatThrownBy(() -> gateway.deleteUser(USER)).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void rejectedKeyIsUpstreamUnavailable() {
        server.expect(requestTo(SUPABASE + "/auth/v1/admin/users/" + USER))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> gateway.deleteUser(USER)).isInstanceOf(UpstreamUnavailableException.class);
    }
}
