package com.pm.filmfuse.user.model;

/**
 * SUSPENDED accounts are refused at authentication. (PENDING_VERIFICATION, ported from the old
 * user-service and never enforced, was removed in V17: Supabase Auth owns email verification.)
 */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED
}
