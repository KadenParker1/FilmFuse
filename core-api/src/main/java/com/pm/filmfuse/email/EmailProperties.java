package com.pm.filmfuse.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param provider    {@code mock} (logs, sends nothing) or {@code smtp}
 * @param fromAddress the From on every message; required by the SMTP adapter
 */
@ConfigurationProperties(prefix = "filmfuse.email")
public record EmailProperties(String provider, String fromAddress) {

    public static final String MOCK_PROVIDER = "mock";
    public static final String SMTP_PROVIDER = "smtp";
}
