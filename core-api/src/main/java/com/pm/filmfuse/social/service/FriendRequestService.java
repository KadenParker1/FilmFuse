package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.ConflictException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.social.dto.FriendRequestDTO;
import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.dto.FriendshipStatusDTO;
import com.pm.filmfuse.social.dto.RequestDirection;
import com.pm.filmfuse.social.mapper.SocialMapper;
import com.pm.filmfuse.social.model.RequestOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Friend requests: send, accept, decline, cancel and list.
 *
 * <p>Block handling is deliberately asymmetric. If the caller blocked the target they are told to
 * unblock first (409). If the target blocked the caller, the request "succeeds" (200
 * REQUEST_SENT) but nothing is stored and nobody is told - a blocked person must not be able to
 * detect the block.
 */
@Service
public class FriendRequestService {

    static final String SELF_REQUEST = "You can't send a friend request to yourself.";
    static final String ALREADY_FRIENDS = "You are already friends.";
    static final String UNBLOCK_FIRST = "Unblock this person first.";
    static final String NO_PENDING_REQUEST = "There is no pending friend request from this person.";

    private final SocialGraphService graph;
    private final SocialUsers users;
    private final ApplicationEventPublisher events;

    public FriendRequestService(SocialGraphService graph, SocialUsers users, ApplicationEventPublisher events) {
        this.graph = graph;
        this.users = users;
        this.events = events;
    }

    @Transactional
    public FriendshipStatusDTO send(UUID callerId, UUID targetId) {
        if (callerId.equals(targetId)) {
            throw new BadRequestException(SELF_REQUEST);
        }
        users.requireActive(targetId);
        RequestOutcome outcome = graph.sendRequest(callerId, targetId);
        FriendshipState state = switch (outcome) {
            case SENT -> {
                events.publishEvent(new FriendRequestSentEvent(callerId, targetId));
                yield FriendshipState.REQUEST_SENT;
            }
            case ACCEPTED_REVERSE -> {
                events.publishEvent(new FriendRequestAcceptedEvent(callerId, targetId));
                yield FriendshipState.FRIENDS;
            }
            case ALREADY_SENT, TARGET_BLOCKED_CALLER -> FriendshipState.REQUEST_SENT;
            case ALREADY_FRIENDS -> throw new ConflictException(ALREADY_FRIENDS);
            case CALLER_BLOCKED_TARGET -> throw new ConflictException(UNBLOCK_FIRST);
        };
        return new FriendshipStatusDTO(targetId, state);
    }

    /** 404 unless {@code requesterId} has a pending request to the caller. */
    @Transactional
    public FriendshipStatusDTO accept(UUID callerId, UUID requesterId) {
        if (!graph.acceptRequest(callerId, requesterId)) {
            throw new NotFoundException(NO_PENDING_REQUEST);
        }
        events.publishEvent(new FriendRequestAcceptedEvent(callerId, requesterId));
        return new FriendshipStatusDTO(requesterId, FriendshipState.FRIENDS);
    }

    /** Idempotent; the requester is not told. */
    public void decline(UUID callerId, UUID requesterId) {
        graph.removeRequest(requesterId, callerId);
    }

    /** Withdraws the caller's own pending request. Idempotent. */
    public void cancel(UUID callerId, UUID targetId) {
        graph.removeRequest(callerId, targetId);
    }

    public List<FriendRequestDTO> list(UUID callerId, RequestDirection direction) {
        return graph.pendingRequests(callerId, direction).stream().map(SocialMapper::toRequestDTO).toList();
    }
}
