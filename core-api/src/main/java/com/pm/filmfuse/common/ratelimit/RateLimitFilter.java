package com.pm.filmfuse.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies {@link RateLimiter} to every request. Sits in the security chain right after bearer-token
 * authentication, so the JWT {@code sub} is known; CORS headers are already on the response, so a
 * browser can read the 429.
 *
 * <p>Each request takes a token from the client address's {@link RateLimitRule#IP} bucket, then -
 * when signed in - from the user's {@link RateLimitRule#USER} bucket and from its category bucket
 * (friend requests, invites, ...) if it has one. The first empty bucket answers 429 with
 * {@code Retry-After} (whole seconds, rounded up) and {@code {message}}. Health checks and CORS
 * preflights are never limited.
 *
 * <p>The client address is {@code request.getRemoteAddr()}: under {@code prod}
 * ({@code server.forward-headers-strategy=framework}) that is the first {@code X-Forwarded-For}
 * entry Railway's proxy passes on.
 *
 * <p>Not a {@code @Component}: Spring Boot would also register it as a plain servlet filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!limiter.isEnabled() || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = pathOf(request);
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Duration> wait = limiter.tryAcquire(RateLimitRule.IP, request.getRemoteAddr());
        String userId = userIdOf(SecurityContextHolder.getContext().getAuthentication());
        if (wait.isEmpty() && userId != null) {
            wait = limiter.tryAcquire(RateLimitRule.USER, userId);
            if (wait.isEmpty()) {
                Optional<RateLimitRule> category = RateLimitRule.categoryOf(request.getMethod(), pathOf(request));
                if (category.isPresent()) {
                    wait = limiter.tryAcquire(category.get(), userId);
                }
            }
        }
        if (wait.isPresent()) {
            reject(response, wait.get());
            return;
        }
        chain.doFilter(request, response);
    }

    static void reject(HttpServletResponse response, Duration wait) throws IOException {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"Too many requests. Try again in " + seconds
                + (seconds == 1 ? " second" : " seconds") + ".\"}");
    }

    private static String userIdOf(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()
                ? token.getToken().getSubject() : null;
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }
}
