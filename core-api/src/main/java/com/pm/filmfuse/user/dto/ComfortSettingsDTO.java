package com.pm.filmfuse.user.dto;

import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.dto.ContentDimension;
import com.pm.filmfuse.reco.dto.ContentLevel;
import java.util.Map;

/**
 * The caller's content comfort settings - hard filters on every recommendation they are part of.
 * Request and response of {@code /me/settings/comfort}.
 *
 * <ul>
 *   <li>{@code maxCertification}: null = no limit.</li>
 *   <li>{@code contentLimits}: the most of each dimension the user accepts; a missing dimension
 *       (or {@code STRONG}) means no limit. Responses list only real limits. Null in a request is
 *       treated as {}.</li>
 *   <li>{@code allowUnrated}: whether movies with an unknown certification or content level may
 *       pass a limit. Missing or null in a request = false; never null in a response. (A boxed
 *       {@code Boolean} only because Jackson 3 refuses a missing primitive.)</li>
 * </ul>
 * Mirrored by hand in client/types/api.ts - keep them in sync.
 */
public record ComfortSettingsDTO(
        Certification maxCertification,
        Map<ContentDimension, ContentLevel> contentLimits,
        Boolean allowUnrated) {

    public ComfortSettingsDTO {
        allowUnrated = Boolean.TRUE.equals(allowUnrated);
    }
}
