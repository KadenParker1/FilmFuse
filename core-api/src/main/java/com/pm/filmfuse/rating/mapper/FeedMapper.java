package com.pm.filmfuse.rating.mapper;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.rating.dto.FeedItemDTO;
import com.pm.filmfuse.rating.model.FeedEntry;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import java.util.UUID;

public final class FeedMapper {

    private FeedMapper() {
    }

    public static FeedItemDTO toDTO(FeedEntry entry, UserSummaryDTO author, MovieSummaryDTO movie, UUID viewer) {
        return new FeedItemDTO(entry.id(), author, movie, entry.certification(), entry.score(), entry.body(),
                entry.containsSpoilers(), entry.visibility(), entry.likeCount(), entry.likedByMe(),
                entry.commentCount(),
                entry.author().id().equals(viewer), entry.createdAt(), entry.updatedAt());
    }
}
