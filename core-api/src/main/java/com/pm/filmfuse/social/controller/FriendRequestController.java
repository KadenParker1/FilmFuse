package com.pm.filmfuse.social.controller;

import com.pm.filmfuse.social.dto.FriendRequestDTO;
import com.pm.filmfuse.social.dto.FriendshipStatusDTO;
import com.pm.filmfuse.social.dto.RequestDirection;
import com.pm.filmfuse.social.dto.SendFriendRequestDTO;
import com.pm.filmfuse.social.service.FriendRequestService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Pending friend requests. */
@RestController
@RequestMapping("/api/v1/friends/requests")
public class FriendRequestController {

    private final FriendRequestService friendRequestService;

    public FriendRequestController(FriendRequestService friendRequestService) {
        this.friendRequestService = friendRequestService;
    }

    /** Newest first. {@code direction} is INCOMING or OUTGOING (400 otherwise). */
    @GetMapping
    public List<FriendRequestDTO> list(@CurrentUser Profile caller, @RequestParam RequestDirection direction) {
        return friendRequestService.list(caller.getId(), direction);
    }

    /**
     * REQUEST_SENT, or FRIENDS when the target had already asked the caller. Idempotent.
     * 400 for oneself, 404 for an unknown user, 409 when already friends or the caller blocked them.
     */
    @PostMapping
    public FriendshipStatusDTO send(@CurrentUser Profile caller, @Valid @RequestBody SendFriendRequestDTO request) {
        return friendRequestService.send(caller.getId(), request.targetUserId());
    }

    /** 404 when {@code requesterId} has no pending request to the caller. */
    @PostMapping("/{requesterId}/accept")
    public FriendshipStatusDTO accept(@CurrentUser Profile caller, @PathVariable UUID requesterId) {
        return friendRequestService.accept(caller.getId(), requesterId);
    }

    /** 204, idempotent. */
    @PostMapping("/{requesterId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@CurrentUser Profile caller, @PathVariable UUID requesterId) {
        friendRequestService.decline(caller.getId(), requesterId);
    }

    /** Cancels the caller's outgoing request. 204, idempotent. */
    @DeleteMapping("/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@CurrentUser Profile caller, @PathVariable UUID targetUserId) {
        friendRequestService.cancel(caller.getId(), targetUserId);
    }
}
