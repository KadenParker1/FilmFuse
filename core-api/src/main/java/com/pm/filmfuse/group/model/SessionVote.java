package com.pm.filmfuse.group.model;

import com.pm.filmfuse.group.dto.Vote;
import java.util.UUID;

/** A row of {@code app.rec_session_votes}. */
public record SessionVote(UUID userId, int movieId, Vote vote) {
}
