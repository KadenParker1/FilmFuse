package com.pm.filmfuse.user.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.user.dto.ProfilePhotoUploadDTO;
import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.gateway.StorageGateway;
import com.pm.filmfuse.user.mapper.ProfileMapper;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Profile photos live in the public {@value #BUCKET} bucket at {@code <userId>/<uuid>.jpg}. The
 * client never holds storage credentials: core-api signs a one-off upload URL, the client uploads
 * to it, then confirms the path. {@code profiles.profile_image_url} stores the public URL.
 */
@Service
public class ProfilePhotoService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

    public static final String BUCKET = "profile-photos";
    /** Supabase Storage's signed-upload lifetime (storage-api default; the sign endpoint takes no expiry). */
    public static final int UPLOAD_URL_TTL_SECONDS = 7200;
    static final String INVALID_PATH = "That is not a photo path issued to you.";
    static final String NOT_UPLOADED = "Upload the photo before confirming it.";

    private final ProfileRepository profileRepository;
    private final StorageGateway storageGateway;

    public ProfilePhotoService(ProfileRepository profileRepository, StorageGateway storageGateway) {
        this.profileRepository = profileRepository;
        this.storageGateway = storageGateway;
    }

    public ProfilePhotoUploadDTO createUploadUrl(Profile profile) {
        String path = profile.getId() + "/" + UUID.randomUUID() + ".jpg";
        String uploadUrl = storageGateway.createSignedUploadUrl(BUCKET, path, UPLOAD_URL_TTL_SECONDS);
        return new ProfilePhotoUploadDTO(uploadUrl, path, UPLOAD_URL_TTL_SECONDS);
    }

    /**
     * Points the profile at an uploaded photo and deletes the previous one (best-effort). The path
     * must be one this endpoint could have issued to the caller - never another user's folder - and
     * the object must exist in storage (SECURITY.md #22), so a profile never points at nothing.
     */
    public ProfileResponseDTO confirm(Profile profile, String path) {
        if (path == null || !ownPhotoPath(profile.getId()).matcher(path).matches()) {
            throw new BadRequestException(INVALID_PATH);
        }
        if (!storageGateway.exists(BUCKET, path)) {
            throw new BadRequestException(NOT_UPLOADED);
        }
        String previousPath = storagePathOf(profile.getProfileImageUrl());
        profile.setProfileImageUrl(storageGateway.publicUrl(BUCKET, path));
        ProfileResponseDTO response = ProfileMapper.toDTO(profileRepository.saveAndFlush(profile));
        if (previousPath != null && !previousPath.equals(path)) {
            deleteQuietly(previousPath);
        }
        return response;
    }

    public ProfileResponseDTO remove(Profile profile) {
        String previousPath = storagePathOf(profile.getProfileImageUrl());
        profile.setProfileImageUrl(null);
        ProfileResponseDTO response = ProfileMapper.toDTO(profileRepository.saveAndFlush(profile));
        if (previousPath != null) {
            deleteQuietly(previousPath);
        }
        return response;
    }

    /** Best-effort: an orphaned object costs a few KB; failing the user's request over it is worse. */
    void deleteQuietly(String path) {
        try {
            storageGateway.delete(BUCKET, path);
        } catch (RuntimeException ex) {
            log.warn("Could not delete profile photo {}/{}; it is now orphaned", BUCKET, path, ex);
        }
    }

    /**
     * The bucket path behind a stored public URL, or null when the URL is not one of ours (or there
     * is no photo) - an unknown URL is never turned into a delete.
     */
    String storagePathOf(String publicUrl) {
        if (publicUrl == null) {
            return null;
        }
        String prefix = storageGateway.publicUrl(BUCKET, "");
        if (!publicUrl.startsWith(prefix)) {
            return null;
        }
        String path = publicUrl.substring(prefix.length());
        return path.isEmpty() ? null : path;
    }

    private static Pattern ownPhotoPath(UUID userId) {
        return Pattern.compile("^" + Pattern.quote(userId.toString()) + "/[0-9a-f-]{36}\\.jpg$");
    }
}
