package com.pm.filmfuse.rating.dto;

/**
 * Which ratings a feed list shows ({@code ?scope=}, required): {@code FRIENDS} = visible ratings by
 * the caller's friends and the caller; {@code EVERYONE} = visible PUBLIC ratings by anyone.
 * Mirrored as a string union in client/types/api.ts.
 */
public enum FeedScope {
    FRIENDS,
    EVERYONE
}
