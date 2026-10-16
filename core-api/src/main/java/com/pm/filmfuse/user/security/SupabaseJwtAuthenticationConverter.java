package com.pm.filmfuse.user.security;

import com.pm.filmfuse.user.model.AccountStatus;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a verified Supabase JWT into an authentication. Two checks beyond the signature:
 * <ul>
 *   <li>{@code sub} must be a UUID (Supabase always issues one); anything else is a 401, not a 500.</li>
 *   <li>A SUSPENDED profile is refused. Supabase cannot un-issue a token, so the ban is enforced here
 *       with one primary-key lookup per request.</li>
 * </ul>
 * A not-yet-provisioned user is fine - {@code @CurrentUser} creates the profile.
 */
public class SupabaseJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(SupabaseJwtAuthenticationConverter.class);

    private final ProfileRepository profileRepository;

    public SupabaseJwtAuthenticationConverter(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = subjectOf(jwt);
        profileRepository.findById(userId)
                .filter(profile -> profile.getStatus() == AccountStatus.SUSPENDED)
                .ifPresent(profile -> {
                    log.info("Rejecting a request from suspended account {}", userId);
                    throw new OAuth2AuthenticationException(
                            new OAuth2Error("account_disabled", "This account is suspended.", null));
                });
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")), jwt.getSubject());
    }

    static UUID subjectOf(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "The token subject is not a user id.", null), ex);
        }
    }
}
