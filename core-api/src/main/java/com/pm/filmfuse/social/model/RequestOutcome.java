package com.pm.filmfuse.social.model;

/** What {@code SocialGraphService.sendRequest} did. */
public enum RequestOutcome {
    /** A new pending request was stored. */
    SENT,
    /** The caller's request was already pending; nothing changed. */
    ALREADY_SENT,
    /** The target had already asked the caller, so this accepted their request. */
    ACCEPTED_REVERSE,
    /** They are friends already; nothing changed. */
    ALREADY_FRIENDS,
    /** The caller has blocked the target; nothing changed. */
    CALLER_BLOCKED_TARGET,
    /** The target has blocked the caller; nothing changed (and the caller must not learn this). */
    TARGET_BLOCKED_CALLER
}
