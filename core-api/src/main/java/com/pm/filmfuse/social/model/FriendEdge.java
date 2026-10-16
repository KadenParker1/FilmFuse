package com.pm.filmfuse.social.model;

import java.time.Instant;

/**
 * One accepted friendship seen from one side: the friend, and the viewer's directional closeness
 * to them. {@code closeness} is already the effective value (see V5__social_graph.sql).
 */
public record FriendEdge(SocialUser friend, boolean explicitClose, float closeness, Instant friendsSince) {
}
