package com.pm.filmfuse.reco.model;

import com.pm.filmfuse.common.json.JsonColumns;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.dto.ContentDimension;
import com.pm.filmfuse.reco.dto.ContentLevel;
import com.pm.filmfuse.reco.dto.EffectiveFilterDTO;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A content comfort filter: one user's settings, a group's cap, or the effective filter of a
 * session. These are <strong>hard</strong> filters - never preferences.
 *
 * <p>Normalised on construction: {@code contentLimits} holds only real limits (a {@code STRONG} or
 * null entry means "no limit" and is dropped), so equal filters compare equal.
 *
 * <p>Eligibility ({@link #isEligible}), as in the Phase 3 contract:
 * <ul>
 *   <li>certification: no cap passes everything; with a cap, a known certification must be at most
 *       the cap and an unknown one passes only when {@code allowUnrated};</li>
 *   <li>each limited dimension: a known level must be at most the limit; an unknown level passes
 *       only when {@code allowUnrated} (or the limit is {@code STRONG}, i.e. no limit).</li>
 * </ul>
 *
 * @param maxCertification null = no certification limit
 * @param contentLimits    limited dimensions only; a missing dimension is not limited
 * @param allowUnrated     whether unknown certification / unknown levels may pass a limit
 */
public record ContentFilter(
        Certification maxCertification,
        Map<ContentDimension, ContentLevel> contentLimits,
        boolean allowUnrated) {

    /** A user who never changed their comfort settings: no limits, unrated content excluded where a limit applies. */
    public static final ContentFilter USER_DEFAULT = new ContentFilter(null, Map.of(), false);

    /**
     * The neutral element of {@link #mostRestrictive}: no limits and unrated allowed. A group cap is
     * a filter like this with a certification and/or limits - it can tighten but never loosen
     * {@code allowUnrated}.
     */
    public static final ContentFilter UNLIMITED = new ContentFilter(null, Map.of(), true);

    public ContentFilter {
        EnumMap<ContentDimension, ContentLevel> limits = new EnumMap<>(ContentDimension.class);
        if (contentLimits != null) {
            contentLimits.forEach((dimension, level) -> {
                if (dimension != null && level != null && level != ContentLevel.STRONG) {
                    limits.put(dimension, level);
                }
            });
        }
        contentLimits = Collections.unmodifiableMap(limits);
    }

    /** A group cap (never restricts unrated content by itself). */
    public static ContentFilter cap(Certification maxCertification, Map<ContentDimension, ContentLevel> contentLimits) {
        return new ContentFilter(maxCertification, contentLimits, true);
    }

    /**
     * Field by field, the most restrictive of {@code filters}: the lowest certification cap, the
     * lowest limit per dimension, and {@code allowUnrated} only if every filter allows it. An empty
     * collection is {@link #UNLIMITED}.
     */
    public static ContentFilter mostRestrictive(Collection<ContentFilter> filters) {
        Certification max = null;
        EnumMap<ContentDimension, ContentLevel> limits = new EnumMap<>(ContentDimension.class);
        boolean allowUnrated = true;
        for (ContentFilter filter : filters) {
            max = Certification.stricter(max, filter.maxCertification());
            filter.contentLimits().forEach((dimension, level) -> limits.merge(dimension, level, ContentLevel::min));
            allowUnrated &= filter.allowUnrated();
        }
        return new ContentFilter(max, limits, allowUnrated);
    }

    /** The limit for {@code dimension}; {@code STRONG} (no limit) when it is not limited. */
    public ContentLevel limit(ContentDimension dimension) {
        return contentLimits.getOrDefault(dimension, ContentLevel.STRONG);
    }

    public boolean hasLimits() {
        return maxCertification != null || !contentLimits.isEmpty();
    }

    /** See the class comment. */
    public boolean isEligible(MovieContent movie) {
        if (maxCertification != null) {
            Certification certification = movie.certification();
            if (certification == null ? !allowUnrated : !certification.isAtMost(maxCertification)) {
                return false;
            }
        }
        for (Map.Entry<ContentDimension, ContentLevel> limit : contentLimits.entrySet()) {
            if (limit.getValue() == ContentLevel.STRONG) {
                continue;
            }
            ContentLevel level = movie.levels().get(limit.getKey());
            if (level == null ? !allowUnrated : !level.isAtMost(limit.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * "Up to PG-13 · mild violence · no sexual content", or "No limits". Describes levels only - it
     * never names a member.
     */
    public String label() {
        List<String> parts = new ArrayList<>();
        if (maxCertification != null) {
            parts.add("up to " + maxCertification.label());
        }
        contentLimits.forEach((dimension, level) -> parts.add(phrase(dimension, level)));
        if (parts.isEmpty()) {
            return "No limits";
        }
        String text = String.join(" · ", parts);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String phrase(ContentDimension dimension, ContentLevel level) {
        String noun = switch (dimension) {
            case VIOLENCE -> "violence";
            case SEXUAL -> "sexual content";
            case LANGUAGE -> level == ContentLevel.NONE ? "bad language" : "language";
            case DRUGS -> "drug content";
            case FRIGHTENING -> "frightening scenes";
        };
        return switch (level) {
            case NONE -> "no " + noun;
            case MILD -> "mild " + noun;
            case MODERATE -> "moderate " + noun;
            case STRONG -> "strong " + noun;
        };
    }

    public EffectiveFilterDTO toDTO() {
        return new EffectiveFilterDTO(maxCertification, new LinkedHashMap<>(contentLimits), allowUnrated, label());
    }

    // ------------------------------------------------------------ storage / ML wire form

    /** {@code {"violence": "mild"}}: lowercase codes, as stored in jsonb and sent to the ML service. */
    public Map<String, String> contentLimitCodes() {
        Map<String, String> codes = new LinkedHashMap<>();
        contentLimits.forEach((dimension, level) -> codes.put(dimension.code(), level.code()));
        return codes;
    }

    /** Parses {@link #contentLimitCodes()} JSON; unknown keys or values are ignored. */
    public static Map<ContentDimension, ContentLevel> limitsFromJson(String json) {
        EnumMap<ContentDimension, ContentLevel> limits = new EnumMap<>(ContentDimension.class);
        JsonColumns.readObject(json).forEach((key, value) -> {
            ContentDimension dimension = ContentDimension.fromCode(key);
            ContentLevel level = value instanceof String text ? ContentLevel.fromCode(text) : null;
            if (dimension != null && level != null) {
                limits.put(dimension, level);
            }
        });
        return limits;
    }

    /** The whole filter as stored in {@code rec_sessions.effective_filter}. */
    public String toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("maxCertification", maxCertification == null ? null : maxCertification.label());
        json.put("contentLimits", contentLimitCodes());
        json.put("allowUnrated", allowUnrated);
        return JsonColumns.write(json);
    }

    @SuppressWarnings("unchecked")
    public static ContentFilter fromJson(String json) {
        Map<String, Object> map = JsonColumns.readObject(json);
        Certification max = map.get("maxCertification") instanceof String label ? Certification.fromLabel(label) : null;
        Map<ContentDimension, ContentLevel> limits = map.get("contentLimits") instanceof Map<?, ?> raw
                ? limitsFromJson(JsonColumns.write(raw)) : Map.of();
        boolean allowUnrated = Boolean.TRUE.equals(map.get("allowUnrated"));
        return new ContentFilter(max, limits, allowUnrated);
    }
}
