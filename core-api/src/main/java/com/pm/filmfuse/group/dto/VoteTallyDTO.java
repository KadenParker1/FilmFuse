package com.pm.filmfuse.group.dto;

/** Votes on one result. {@code vetoed}: at least one member vetoed it. Mirrored in client/types/api.ts. */
public record VoteTallyDTO(
        int up,
        int down,
        boolean vetoed) {
}
