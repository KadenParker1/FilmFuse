package com.pm.filmfuse.reco.dto;

import java.util.Map;

/**
 * The comfort filter a session (or group) actually applies: per field the most restrictive value
 * across the members plus the group's cap. {@code contentLimits} lists only limited dimensions (a
 * missing one is {@code STRONG}, i.e. no limit). {@code label} is human readable ("Up to PG-13 ·
 * mild violence") and never says which member caused a limit. Mirrored by hand in
 * client/types/api.ts - keep them in sync.
 */
public record EffectiveFilterDTO(
        Certification maxCertification,
        Map<ContentDimension, ContentLevel> contentLimits,
        boolean allowUnrated,
        String label) {
}
