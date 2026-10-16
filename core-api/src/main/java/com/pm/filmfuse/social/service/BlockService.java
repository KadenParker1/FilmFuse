package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.mapper.SocialMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Blocking. A block removes any friendship, pending requests (both ways) and closeness between the
 * two; afterwards the blocked person cannot find, view or befriend the blocker.
 */
@Service
public class BlockService {

    static final String SELF_BLOCK = "You can't block yourself.";

    private final SocialGraphService graph;
    private final SocialUsers users;

    public BlockService(SocialGraphService graph, SocialUsers users) {
        this.graph = graph;
        this.users = users;
    }

    /** Most recently blocked first. */
    public List<UserSummaryDTO> list(UUID callerId) {
        return graph.blockedBy(callerId).stream()
                .map(blocked -> SocialMapper.toSummaryDTO(blocked.user(), FriendshipState.BLOCKED))
                .toList();
    }

    /** Idempotent. 400 for oneself, 404 for an unknown user. */
    public void block(UUID callerId, UUID targetId) {
        if (callerId.equals(targetId)) {
            throw new BadRequestException(SELF_BLOCK);
        }
        // Not requireActive: a suspended account can still be blocked.
        users.requireExists(targetId);
        graph.block(callerId, targetId);
    }

    /** Idempotent. */
    public void unblock(UUID callerId, UUID targetId) {
        graph.unblock(callerId, targetId);
    }
}
