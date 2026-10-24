package com.pm.filmfuse.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.server.PathContainer;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The one place the admin surface is guarded. {@code SecurityConfig} requires {@code ROLE_ADMIN}
 * (granted from {@code profiles.role} by
 * {@link com.pm.filmfuse.user.security.SupabaseJwtAuthenticationConverter}) for every request under
 * {@link #PATH_PATTERN}; controllers under it carry no checks of their own.
 *
 * <p>A signed-in non-admin is answered exactly like a request for a path that does not exist
 * ({@code sendError(404)}), so the admin surface is not revealed. Unauthenticated requests get the
 * normal 401, as on every other API path.
 */
public final class AdminAccess {

    public static final String PREFIX = "/api/v1/admin";
    public static final String PATH_PATTERN = PREFIX + "/**";

    /**
     * Matched the way the security rule and Spring MVC match: on percent-decoded segments, so
     * {@code /api/v1/%61dmin/settings} (which MVC routes to the admin controller and the security
     * rule refuses) is answered 404 too, not a revealing 403.
     */
    private static final PathPattern PATTERN = PathPatternParser.defaultInstance.parse(PATH_PATTERN);

    private AdminAccess() {
    }

    public static boolean isAdminPath(String path) {
        return path != null && PATTERN.matches(PathContainer.parsePath(path));
    }

    public static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    /** 404 for admin paths, Spring Security's default 403 everywhere else. */
    public static AccessDeniedHandler accessDeniedHandler() {
        AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) -> {
            if (isAdminPath(pathOf(request))) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            fallback.handle(request, response, ex);
        };
    }
}
