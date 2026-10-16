package com.pm.filmfuse.user.model;

/** Ported from user-service. SUSPENDED accounts are refused at authentication. */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED,
    PENDING_VERIFICATION
}
