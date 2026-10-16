package com.pm.filmfuse.user.dto;

/**
 * Where to upload a new profile photo. The client {@code PUT}s the JPEG bytes to {@code uploadUrl}
 * with {@code Content-Type: image/jpeg}, then confirms {@code path} via {@code POST /me/profile-photo}.
 * Mirrored by hand in client/types/api.ts - keep them in sync.
 */
public record ProfilePhotoUploadDTO(String uploadUrl, String path, int expiresInSeconds) {
}
