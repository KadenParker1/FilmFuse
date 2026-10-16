package com.pm.filmfuse.group.model;

import com.pm.filmfuse.group.dto.SessionFiltersDTO;
import com.pm.filmfuse.group.dto.SessionStatus;
import com.pm.filmfuse.group.dto.Strategy;
import com.pm.filmfuse.reco.model.ContentFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code app.rec_sessions} (plus the group's current name). {@code storedStatus} is OPEN or
 * DECIDED; use {@link #status} for what the API reports.
 */
public record Session(
        UUID id,
        UUID groupId,
        String groupName,
        UUID createdBy,
        SessionFiltersDTO filters,
        ContentFilter effectiveFilter,
        Strategy strategy,
        SessionStatus storedStatus,
        Integer pickedMovieId,
        int memberCount,
        Instant createdAt) {

    /** OPEN sessions stop accepting votes, refreshes and picks after this long. */
    public static final Duration OPEN_FOR = Duration.ofHours(24);

    /** OPEN sessions older than {@link #OPEN_FOR} are EXPIRED. */
    public SessionStatus status(Instant now) {
        if (storedStatus == SessionStatus.OPEN && createdAt.plus(OPEN_FOR).isBefore(now)) {
            return SessionStatus.EXPIRED;
        }
        return storedStatus;
    }
}
