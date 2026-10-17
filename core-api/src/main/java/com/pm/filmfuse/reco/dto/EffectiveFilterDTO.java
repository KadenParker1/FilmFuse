package com.pm.filmfuse.reco.dto;

import java.util.Map;

/**
 * The comfort filter a session (or group) actually applies: per field the most restrictive value
 * across the members plus the group's cap. {@code contentLimits} lists only limited dimensions (a
 * missing one is {@code STRONG}, i.e. no limit). {@code label} is a self-contained phrase the
 * client shows verbatim (no prefix): the certification part first, then one part per limited
 * dimension, joined by " · ", first letter capitalised - e.g. "Rated PG-13 or below · mild violence
 * at most · no sexual content", "Rated G only", "Moderate drug content at most", or "No limits" when
 * nothing is limited. It never says which member caused a limit. Mirrored by hand in
 * client/types/api.ts - keep them in sync.
 */
public record EffectiveFilterDTO(
        Certification maxCertification,
        Map<ContentDimension, ContentLevel> contentLimits,
        boolean allowUnrated,
        String label) {
}
