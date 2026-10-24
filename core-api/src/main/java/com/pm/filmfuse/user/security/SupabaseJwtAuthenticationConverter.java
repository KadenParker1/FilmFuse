package com.pm.filmfuse.user.security;

import com.pm.filmfuse.user.model.AccountStatus;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.DeletedAccountRepository;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.util.List;
import java.util.Optional;
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
 *   <li>A deleted account (tombstoned in {@code deleted_accounts}) is a 401 {@code account_deleted}: its
 *       token outlives the Supabase user, and must not resurrect the profile.</li>
 *   <li>A SUSPENDED profile is refused. Supabase cannot un-issue a token, so the ban is enforced here
 *       with one primary-key lookup per request.</li>
 *   <li>The same lookup grants {@code ROLE_ADMIN} when {@code profiles.role = 'admin'} (V21).</li>
 * </ul>
 * A not-yet-provisioned user is fine - {@code @CurrentUser} creates the profile.
 */
public class SupabaseJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String ROLE_USER = "ROLE_USER";
    /** Granted when {@code profiles.role = 'admin'}; required by every {@code /api/v1/admin/**} request. */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private static final Logger log = LoggerFactory.getLogger(SupabaseJwtAuthenticationConverter.class);

    private final ProfileRepository profileRepository;
    private final DeletedAccountRepository deletedAccountRepository;

    public SupabaseJwtAuthenticationConverter(ProfileRepository profileRepository,
                                              DeletedAccountRepository deletedAccountRepository) {
        this.profileRepository = profileRepository;
        this.deletedAccountRepository = deletedAccountRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = subjectOf(jwt);
        if (deletedAccountRepository.isDeleted(userId)) {
            log.info("Rejecting a token for deleted account {}", userId);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("account_deleted", "This account has been deleted.", null));
        }
        Optional<Profile> profile = profileRepository.findById(userId);
        if (profile.isPresent() && profile.get().getStatus() == AccountStatus.SUSPENDED) {
            log.info("Rejecting a request from suspended account {}", userId);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("account_disabled", "This account is suspended.", null));
        }
        // The admin authority comes from the database row only, never from a token claim, so it
        // follows a grant or revoke on the next request.
        List<SimpleGrantedAuthority> authorities = profile.isPresent() && profile.get().isAdmin()
                ? List.of(new SimpleGrantedAuthority(ROLE_USER), new SimpleGrantedAuthority(ROLE_ADMIN))
                : List.of(new SimpleGrantedAuthority(ROLE_USER));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
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
