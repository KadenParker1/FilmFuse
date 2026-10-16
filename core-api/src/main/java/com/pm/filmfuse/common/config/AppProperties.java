package com.pm.filmfuse.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param webUrl the web app's origin, no trailing slash ({@code filmfuse.app.web-url}, env
 *               {@code APP_WEB_URL}); the base of invite links and of links in emails
 */
@ConfigurationProperties(prefix = "filmfuse.app")
public record AppProperties(String webUrl) {

    public AppProperties {
        if (webUrl == null || webUrl.isBlank()) {
            throw new IllegalStateException("filmfuse.app.web-url must be set");
        }
        webUrl = webUrl.trim().replaceAll("/+$", "");
    }
}
