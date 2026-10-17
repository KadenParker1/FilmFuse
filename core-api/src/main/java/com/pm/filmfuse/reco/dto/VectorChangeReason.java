package com.pm.filmfuse.reco.dto;

/**
 * Why a vector was marked dirty (and so why the next version was built): the latest taste signal
 * wins. Stored by name. Mirrored as a string union in client/types/api.ts.
 */
public enum VectorChangeReason {
    ONBOARDING,
    TASTE_EDIT,
    RATING,
    DISMISS,
    PICK
}
