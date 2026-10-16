package com.pm.filmfuse.support;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * MockMvc {@code jwt()} post-processors that mimic a verified Supabase access token. They inject
 * the security context directly, so no JWKS is contacted.
 */
public final class JwtTestSupport {

    private JwtTestSupport() {
    }

    /** RFC 2606 reserved domain - can never route. */
    public static String emailFor(UUID userId) {
        return userId + "@example.com";
    }

    public static JwtRequestPostProcessor supabaseUser(UUID userId) {
        return jwt()
                .jwt(builder -> builder.subject(userId.toString())
                        .claim("email", emailFor(userId))
                        .claim("role", "authenticated"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /** As an OAuth (Google / Apple) sign-in, which carries the person's name in user_metadata. */
    public static JwtRequestPostProcessor supabaseUserWithName(UUID userId, String fullName) {
        return jwt()
                .jwt(builder -> builder.subject(userId.toString())
                        .claim("email", emailFor(userId))
                        .claim("role", "authenticated")
                        .claim("user_metadata", Map.of("full_name", fullName)))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
