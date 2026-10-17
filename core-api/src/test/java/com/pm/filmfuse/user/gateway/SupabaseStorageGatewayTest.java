package com.pm.filmfuse.user.gateway;

import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import java.net.ConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseStorageGatewayTest {

    private static final String SUPABASE = "https://proj.supabase.test";
    private static final String PATH = "6f1c1d0e-2a8b-4c3d-9e4f-5a6b7c8d9e0f/0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jpg";

    private MockRestServiceServer server;
    private SupabaseStorageGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // Trailing slash on purpose: it must not double up in request URLs.
        gateway = new SupabaseStorageGateway(builder, new SupabaseProperties(SUPABASE + "/", "service-key"));
    }

    @Test
    void signedUploadPostsToSignEndpointWithServiceKeyAndReturnsAbsoluteUrl() {
        server.expect(requestTo(SUPABASE + "/storage/v1/object/upload/sign/profile-photos/" + PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer service-key"))
                .andExpect(header("apikey", "service-key"))
                .andRespond(withSuccess("""
                        {"url": "/object/upload/sign/profile-photos/%s?token=abc.def"}
                        """.formatted(PATH), MediaType.APPLICATION_JSON));

        String url = gateway.createSignedUploadUrl("profile-photos", PATH, 7200);

        server.verify();
        assertThat(url).isEqualTo(SUPABASE + "/storage/v1/object/upload/sign/profile-photos/" + PATH + "?token=abc.def");
    }

    @Test
    void signedUploadKeepsAnAlreadyAbsoluteUrl() {
        server.expect(requestTo(SUPABASE + "/storage/v1/object/upload/sign/profile-photos/" + PATH))
                .andRespond(withSuccess("{\"url\":\"https://cdn.test/upload?token=t\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.createSignedUploadUrl("profile-photos", PATH, 7200)).isEqualTo("https://cdn.test/upload?token=t");
    }

    @Test
    void signedUploadFailureIsUpstreamUnavailable() {
        server.expect(requestTo(SUPABASE + "/storage/v1/object/upload/sign/profile-photos/" + PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"Bucket not found\"}"));

        assertThatThrownBy(() -> gateway.createSignedUploadUrl("profile-photos", PATH, 7200))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessage("Photo storage is temporarily unavailable.");
    }

    @Test
    void signedUploadWithoutUrlIsUpstreamUnavailable() {
        server.expect(requestTo(SUPABASE + "/storage/v1/object/upload/sign/profile-photos/" + PATH))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createSignedUploadUrl("profile-photos", PATH, 7200))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void publicUrlIsBuiltWithoutARequest() {
        assertThat(gateway.publicUrl("profile-photos", PATH))
                .isEqualTo(SUPABASE + "/storage/v1/object/public/profile-photos/" + PATH);
        server.verify();
    }

    @Test
    void deleteSendsPrefixesBodyToBucketEndpoint() {
        server.expect(requestTo(SUPABASE + "/storage/v1/object/profile-photos"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer service-key"))
                .andExpect(header("apikey", "service-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"prefixes\": [\"" + PATH + "\"]}", true))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        gateway.delete("profile-photos", PATH);

        server.verify();
    }

    @Test
    void deleteFailureIsUpstreamUnavailable() {
        server.expect(requestTo(SUPABASE + "/storage/v1/object/profile-photos"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> gateway.delete("profile-photos", PATH))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void existsIsAnAuthenticatedHeadOnTheObject() {
        server.expect(requestTo(SUPABASE + "/storage/v1/object/profile-photos/" + PATH))
                .andExpect(method(HttpMethod.HEAD))
                .andExpect(header("Authorization", "Bearer service-key"))
                .andExpect(header("apikey", "service-key"))
                .andRespond(withSuccess());

        assertThat(gateway.exists("profile-photos", PATH)).isTrue();
        server.verify();
    }

    @Test
    void existsTreats400And404AsMissing() {
        // storage-api answers a missing object with 400 + {"statusCode": "404"}; some versions use 404.
        server.expect(requestTo(SUPABASE + "/storage/v1/object/profile-photos/" + PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(SUPABASE + "/storage/v1/object/profile-photos/" + PATH))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(gateway.exists("profile-photos", PATH)).isFalse();
        assertThat(gateway.exists("profile-photos", PATH)).isFalse();
        server.verify();
    }

    @Test
    void existsFailureIsUpstreamUnavailableNotFalse() {
        server.expect(requestTo(SUPABASE + "/storage/v1/object/profile-photos/" + PATH))
                .andRespond(withServerError());
        server.expect(requestTo(SUPABASE + "/storage/v1/object/profile-photos/" + PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(SUPABASE + "/storage/v1/object/profile-photos/" + PATH))
                .andRespond(withException(new ConnectException("refused")));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> gateway.exists("profile-photos", PATH))
                    .isInstanceOf(UpstreamUnavailableException.class)
                    .hasMessage("Photo storage is temporarily unavailable.");
        }
        server.verify();
    }

    @Test
    void connectionFailureIsUpstreamUnavailable() {
        server.expect(requestTo(SUPABASE + "/storage/v1/object/profile-photos"))
                .andRespond(withException(new ConnectException("refused")));

        assertThatThrownBy(() -> gateway.delete("profile-photos", PATH))
                .isInstanceOf(UpstreamUnavailableException.class);
    }
}
