package com.pm.filmfuse.movie.content;

import com.pm.filmfuse.reco.dto.ContentLevel;

/**
 * A derived content level and how sure the provider is of it (0-1). Confidence is stored for
 * analysis and future tuning; the comfort filter treats every stored level as known.
 */
public record ContentLevelEstimate(ContentLevel level, float confidence) {

    public ContentLevelEstimate {
        if (level == null) {
            throw new IllegalArgumentException("level is required");
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be within 0-1");
        }
    }
}
