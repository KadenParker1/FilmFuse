package com.pm.filmfuse.user.gateway;

import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Supabase Storage REST API ({@code <SUPABASE_URL>/storage/v1}) with the service-role key, sent as
 * both {@code Authorization: Bearer} and {@code apikey}.
 *
 * <p>Object paths are concatenated, not expanded as URI variables: a template variable would encode
 * the {@code /} inside {@code <userId>/<uuid>.jpg}. Callers only pass validated paths.
 */
public class SupabaseStorageGateway implements StorageGateway {

    static final String UNAVAILABLE = "Photo storage is temporarily unavailable.";

    private final RestClient restClient;
    private final String storageUrl;

    public SupabaseStorageGateway(RestClient.Builder builder, SupabaseProperties properties) {
        this.storageUrl = properties.url() + "/storage/v1";
        this.restClient = builder
                .baseUrl(storageUrl)
                .defaultHeader("Authorization", "Bearer " + properties.serviceRoleKey())
                .defaultHeader("apikey", properties.serviceRoleKey())
                .build();
    }

    /**
     * {@code POST /object/upload/sign/{bucket}/{path}} answers {@code {"url": "/object/upload/sign/...?token=..."}},
     * relative to {@code /storage/v1}. The lifetime is storage-api's own setting (2 hours by
     * default) - the endpoint takes no expiry - so {@code expiresInSeconds} is informational here.
     */
    @Override
    public String createSignedUploadUrl(String bucket, String path, int expiresInSeconds) {
        SignedUploadResponse response;
        try {
            response = restClient.post()
                    .uri("/object/upload/sign/" + bucket + "/" + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .body(SignedUploadResponse.class);
        } catch (RestClientException ex) {
            throw new UpstreamUnavailableException(UNAVAILABLE, ex);
        }
        if (response == null || response.url() == null || response.url().isBlank()) {
            throw new UpstreamUnavailableException(UNAVAILABLE,
                    new IllegalStateException("Supabase Storage returned no signed upload URL"));
        }
        String url = response.url();
        return url.startsWith("http") ? url : storageUrl + (url.startsWith("/") ? url : "/" + url);
    }

    @Override
    public String publicUrl(String bucket, String path) {
        return storageUrl + "/object/public/" + bucket + "/" + path;
    }

    /**
     * {@code HEAD /object/{bucket}/{path}} (authenticated; the same call supabase-js
     * {@code exists()} makes). 2xx = exists. storage-api reports a missing object or bucket as 400
     * (with a {@code "statusCode": "404"} body) or 404, so both mean "no"; anything else is an outage.
     */
    @Override
    public boolean exists(String bucket, String path) {
        try {
            HttpStatusCode status = restClient.head()
                    .uri("/object/" + bucket + "/" + path)
                    .exchange((request, response) -> response.getStatusCode());
            if (status.is2xxSuccessful()) {
                return true;
            }
            if (status.value() == 400 || status.value() == 404) {
                return false;
            }
            throw new UpstreamUnavailableException(UNAVAILABLE,
                    new IllegalStateException("Supabase Storage HEAD returned " + status.value()));
        } catch (RestClientException ex) {
            throw new UpstreamUnavailableException(UNAVAILABLE, ex);
        }
    }

    /** {@code DELETE /object/{bucket}} with {@code {"prefixes": [path]}}; a missing object is a 200 with an empty list. */
    @Override
    public void delete(String bucket, String path) {
        try {
            restClient.method(HttpMethod.DELETE)
                    .uri("/object/" + bucket)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("prefixes", List.of(path)))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new UpstreamUnavailableException(UNAVAILABLE, new IllegalStateException(
                                "Supabase Storage delete returned " + response.getStatusCode().value()));
                    })
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new UpstreamUnavailableException(UNAVAILABLE, ex);
        }
    }

    record SignedUploadResponse(String url) {
    }
}
