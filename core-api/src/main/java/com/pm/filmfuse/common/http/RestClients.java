package com.pm.filmfuse.common.http;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** {@link RestClient} builders for outbound calls. Every outbound call gets explicit timeouts. */
public final class RestClients {

    private RestClients() {
    }

    /**
     * A builder on the JDK {@link HttpClient}, which (unlike {@code HttpURLConnection}) also sends a
     * body with DELETE - Supabase Storage's delete takes one.
     *
     * <p>Pinned to HTTP/1.1: the JDK client's default (HTTP/2) sends an {@code Upgrade: h2c} request
     * to plain-http hosts, and uvicorn (python-ml-service over Railway's private network) rejects the
     * upgrade and drops the request body - every ML call then fails validation with 422.
     */
    public static RestClient.Builder withTimeouts(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = newHttpClient(connectTimeout);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }

    static HttpClient newHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
