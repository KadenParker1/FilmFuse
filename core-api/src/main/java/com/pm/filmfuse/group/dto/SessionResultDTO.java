package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.reco.dto.Certification;
import java.util.Map;
import java.util.UUID;

/**
 * One recommended movie in a session. {@code rank} is the ranker's position (1 = best; refreshed
 * results continue the numbering). {@code certification} is the movie's at recommendation time
 * (null = unknown). {@code memberScores} maps member user id to that member's predicted score (only for the members
 * listed in the session's {@code members}).
 * {@code myVote} is the caller's vote, null if none. Mirrored in client/types/api.ts.
 */
public record SessionResultDTO(
        int rank,
        MovieSummaryDTO movie,
        Certification certification,
        double groupScore,
        Map<UUID, Double> memberScores,
        String explanation,
        VoteTallyDTO votes,
        Vote myVote) {
}
