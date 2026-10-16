package com.pm.filmfuse.group.realtime;

import java.util.UUID;

/**
 * Published inside the transaction that changed a session; broadcast once it commits.
 *
 * @param type "vote", "refresh" or "pick"
 */
public record SessionUpdatedEvent(UUID sessionId, String type) {

    public static final String VOTE = "vote";
    public static final String REFRESH = "refresh";
    public static final String PICK = "pick";
}
