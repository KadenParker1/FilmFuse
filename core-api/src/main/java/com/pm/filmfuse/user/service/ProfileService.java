package com.pm.filmfuse.user.service;

import com.pm.filmfuse.common.exception.ConflictException;
import com.pm.filmfuse.common.exception.ForbiddenException;
import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.dto.UpdateProfileRequestDTO;
import com.pm.filmfuse.user.dto.UsernameAvailabilityDTO;
import com.pm.filmfuse.user.mapper.ProfileMapper;
import com.pm.filmfuse.user.model.AccountStatus;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.DeletedAccountRepository;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private static final int DISPLAY_NAME_MAX = 77;
    static final String USERNAME_TAKEN = "That username is already taken.";

    private final ProfileRepository profileRepository;
    private final DeletedAccountRepository deletedAccountRepository;

    public ProfileService(ProfileRepository profileRepository, DeletedAccountRepository deletedAccountRepository) {
        this.profileRepository = profileRepository;
        this.deletedAccountRepository = deletedAccountRepository;
    }

    /**
     * The caller's profile, created on first sight of their Supabase user id. Deliberately not one
     * transaction: if two first requests race, the loser's insert fails on the primary key and it
     * simply re-reads the winner's row.
     *
     * <p>Also keeps {@code profiles.email} in step with the token's {@code email} claim (the user may
     * have changed it in Supabase). A token without the claim leaves the stored address alone.
     */
    public Profile resolve(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        Profile profile = profileRepository.findById(userId).orElseGet(() -> provision(userId, jwt));
        // Belt and braces: SupabaseJwtAuthenticationConverter already refuses suspended accounts.
        if (profile.getStatus() == AccountStatus.SUSPENDED) {
            throw new ForbiddenException("This account is suspended.");
        }
        String email = emailFrom(jwt);
        if (email != null && !Objects.equals(email, profile.getEmail())) {
            profileRepository.updateEmail(userId, email);
            profile.setEmail(email);
        }
        return profile;
    }

    public ProfileResponseDTO get(Profile profile) {
        return ProfileMapper.toDTO(profile);
    }

    public ProfileResponseDTO update(Profile profile, UpdateProfileRequestDTO request) {
        String username = request.username();
        if (username != null && !username.equals(profile.getUsername())
                && profileRepository.existsByUsernameAndIdNot(username, profile.getId())) {
            throw new ConflictException(USERNAME_TAKEN);
        }
        ProfileMapper.applyUpdate(profile, request);
        try {
            return ProfileMapper.toDTO(profileRepository.saveAndFlush(profile));
        } catch (DataIntegrityViolationException ex) {
            // Lost a race for the same username between the check above and the insert.
            log.debug("Username update lost a uniqueness race", ex);
            throw new ConflictException(USERNAME_TAKEN);
        }
    }

    public UsernameAvailabilityDTO checkUsername(Profile caller, String rawUsername) {
        String username = UpdateProfileRequestDTO.normaliseUsername(rawUsername);
        if (!UpdateProfileRequestDTO.isValidUsername(username)) {
            return new UsernameAvailabilityDTO(username, false, false);
        }
        boolean available = username.equals(caller.getUsername())
                || !profileRepository.existsByUsernameAndIdNot(username, caller.getId());
        return new UsernameAvailabilityDTO(username, true, available);
    }

    private Profile provision(UUID userId, Jwt jwt) {
        // Belt and braces: SupabaseJwtAuthenticationConverter already 401s a deleted account's token.
        if (deletedAccountRepository.isDeleted(userId)) {
            throw new ForbiddenException("This account has been deleted.");
        }
        Profile profile = new Profile(userId);
        profile.setDisplayName(displayNameFrom(jwt));
        profile.setEmail(emailFrom(jwt));
        try {
            Profile saved = profileRepository.saveAndFlush(profile);
            log.info("Provisioned profile {}", userId);
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent provisioning of {}; re-reading", userId);
            return profileRepository.findById(userId).orElseThrow(() -> ex);
        }
    }

    /** The {@code email} claim, trimmed; null when absent or blank (e.g. a phone sign-in). */
    static String emailFrom(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email == null || email.isBlank() ? null : email.trim();
    }

    /** Google / Apple sign-in put the person's name in {@code user_metadata}; a sensible default. */
    private static String displayNameFrom(Jwt jwt) {
        Object metadata = jwt.getClaims().get("user_metadata");
        if (metadata instanceof Map<?, ?> map) {
            for (String key : new String[] {"full_name", "name"}) {
                if (map.get(key) instanceof String name && !name.isBlank()) {
                    String trimmed = name.trim();
                    return trimmed.length() > DISPLAY_NAME_MAX ? trimmed.substring(0, DISPLAY_NAME_MAX) : trimmed;
                }
            }
        }
        return null;
    }
}
