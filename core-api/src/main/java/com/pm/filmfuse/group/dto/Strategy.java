package com.pm.filmfuse.group.dto;

/**
 * How the ML service aggregates members' preferences (the owner's algorithm decides what each one
 * means). The API uses the constant names; the ML contract uses {@link #mlValue()}. Mirrored as a
 * string union in client/types/api.ts.
 */
public enum Strategy {
    MEAN("mean"),
    LEAST_MISERY("least_misery"),
    MOST_PLEASURE("most_pleasure"),
    CLOSENESS_WEIGHTED("closeness_weighted"),
    FAIRNESS("fairness");

    private final String mlValue;

    Strategy(String mlValue) {
        this.mlValue = mlValue;
    }

    /** The ML contract's lowercase value ("least_misery"). */
    public String mlValue() {
        return mlValue;
    }
}
