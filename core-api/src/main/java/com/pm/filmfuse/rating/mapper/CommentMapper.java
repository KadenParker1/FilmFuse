package com.pm.filmfuse.rating.mapper;

import com.pm.filmfuse.rating.dto.CommentDTO;
import com.pm.filmfuse.rating.model.CommentEntry;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import java.util.UUID;

public final class CommentMapper {

    private CommentMapper() {
    }

    public static CommentDTO toDTO(CommentEntry entry, UserSummaryDTO author, UUID viewer) {
        boolean mine = entry.author().id().equals(viewer);
        return new CommentDTO(entry.id(), entry.ratingId(), author, entry.body(), entry.createdAt(), mine,
                mine || entry.ratingAuthorId().equals(viewer));
    }
}
