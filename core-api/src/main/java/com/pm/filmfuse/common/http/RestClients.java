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
     */
    public static RestClient.Builder withTimeouts(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
