package com.pm.filmfuse.admin.dto;

import com.pm.filmfuse.user.model.AccountStatus;

/**
 * What a report is about, for the moderation queue. {@code exists} is false once the target was
 * deleted. {@code author} is the rating's or comment's author, or the reported user. {@code text} is
 * the rating note or comment body, or the user's bio (at most 500 characters). {@code movieTitle} and
 * {@code score} are set for a rating (and {@code movieTitle} for a comment, of the rating it is on).
 * {@code authorStatus} shows whether that account is already suspended. Mirrored by hand in client/types/api.ts.
 */
public record ReportTargetPreviewDTO(
        boolean exists,
        AdminUserRefDTO author,
        String text,
        Integer movieId,
        String movieTitle,
        Integer score,
        AccountStatus authorStatus) {
}
