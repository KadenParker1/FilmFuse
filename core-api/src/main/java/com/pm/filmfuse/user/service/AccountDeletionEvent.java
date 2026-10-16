package com.pm.filmfuse.user.service;

import java.util.UUID;

/**
 * Published synchronously inside the account-deletion transaction, just before the profile row is
 * deleted, so other modules can clean up in ways a foreign-key cascade cannot (e.g. handing over
 * group ownership). A listener that throws aborts the deletion.
 */
public record AccountDeletionEvent(UUID userId) {
}
