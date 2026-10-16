package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.dto.ContentDimension;
import com.pm.filmfuse.reco.dto.ContentLevel;
import java.util.Map;

/**
 * A group's comfort cap, set by the owner (e.g. a kids group at PG). It only ever tightens the
 * members' own limits. {@code maxCertification} null = no cap; {@code contentLimits} lists capped
 * dimensions only (null in a request = {}). Mirrored by hand in client/types/api.ts.
 */
public record ComfortCapDTO(
        Certification maxCertification,
        Map<ContentDimension, ContentLevel> contentLimits) {
}
