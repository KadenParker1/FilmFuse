package com.pm.filmfuse.common.ratelimit;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The rate limits and their defaults (each overridable as
 * {@code filmfuse.rate-limit.limits.<name>.capacity / .period}).
 *
 * <ul>
 *   <li>{@link #IP} - every request from one client address (authenticated or not).</li>
 *   <li>{@link #USER} - every request by one signed-in user (JWT {@code sub}).</li>
 *   <li>The rest are per user and apply to writes that send email or are abuse-prone, on top of
 *       {@link #USER}.</li>
 * </ul>
 */
public enum RateLimitRule {

    IP("ip", 600, Duration.ofMinutes(1)),
    USER("user", 120, Duration.ofMinutes(1)),
    /** Sending a friend request emails the target. */
    FRIEND_REQUESTS("friend-requests", 30, Duration.ofHours(1),
            "/api/v1/friends/requests"),
    /** Invite links, redeeming them, group invites and adding people to a session (which email every invitee). */
    INVITES("invites", 20, Duration.ofHours(1),
            "/api/v1/invites", "/api/v1/invites/{token}/redeem", "/api/v1/groups", "/api/v1/groups/{id}/members",
            "/api/v1/sessions/{id}/members"),
    REPORTS("reports", 20, Duration.ofHours(1),
            "/api/v1/reports"),
    /** Each comment emails the rating's author. */
    COMMENTS("comments", 60, Duration.ofHours(1),
            "/api/v1/ratings/{id}/comments"),
    /** Starting a session emails every member and calls the ML service. */
    SESSIONS("sessions", 30, Duration.ofHours(1),
            "/api/v1/sessions"),
    /** Each upload URL is a signed write into storage. */
    PHOTO_UPLOAD("photo-upload", 20, Duration.ofHours(1),
            "/api/v1/me/profile-photo/upload-url");

    private final String key;
    private final int defaultCapacity;
    private final Duration defaultPeriod;
    private final List<PathPattern> postPaths;

    RateLimitRule(String key, int defaultCapacity, Duration defaultPeriod, String... postPaths) {
        this.key = key;
        this.defaultCapacity = defaultCapacity;
        this.defaultPeriod = defaultPeriod;
        this.postPaths = Arrays.stream(postPaths).map(PathPatternParser.defaultInstance::parse).toList();
    }

    /** The property name ({@code filmfuse.rate-limit.limits.<key>}) and the bucket-key prefix. */
    public String key() {
        return key;
    }

    public int defaultCapacity() {
        return defaultCapacity;
    }

    public Duration defaultPeriod() {
        return defaultPeriod;
    }

    /** The per-category rule (never IP / USER) for a request, if any. */
    public static Optional<RateLimitRule> categoryOf(String method, String path) {
        if (!HttpMethod.POST.matches(method)) {
            return Optional.empty();
        }
        PathContainer container = PathContainer.parsePath(path);
        for (RateLimitRule rule : values()) {
            for (PathPattern pattern : rule.postPaths) {
                if (pattern.matches(container)) {
                    return Optional.of(rule);
                }
            }
        }
        return Optional.empty();
    }
}
