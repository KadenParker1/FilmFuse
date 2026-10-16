package com.pm.filmfuse.social.controller;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.social.dto.FriendDTO;
import com.pm.filmfuse.social.dto.FriendSuggestionDTO;
import com.pm.filmfuse.social.dto.UpdateFriendDTO;
import com.pm.filmfuse.social.service.FriendService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's friends and friend suggestions. Requests are in {@link FriendRequestController}. */
@RestController
@RequestMapping("/api/v1/friends")
public class FriendController {

    private final FriendService friendService;

    public FriendController(FriendService friendService) {
        this.friendService = friendService;
    }

    /** Close friends first, then by closeness, then username. */
    @GetMapping
    public PageDTO<FriendDTO> list(@CurrentUser Profile caller, @RequestParam(required = false) String cursor) {
        return friendService.list(caller.getId(), cursor);
    }

    /** Friends of friends, most mutual friends first. */
    @GetMapping("/suggestions")
    public List<FriendSuggestionDTO> suggestions(
            @CurrentUser Profile caller,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "Must be between 1 and 50")
            @Max(value = 50, message = "Must be between 1 and 50") int limit) {
        return friendService.suggestions(caller.getId(), limit);
    }

    /** The close-friend flag. 404 when not friends. */
    @PatchMapping("/{userId}")
    public FriendDTO update(@CurrentUser Profile caller, @PathVariable UUID userId,
                            @Valid @RequestBody UpdateFriendDTO request) {
        return friendService.update(caller.getId(), userId, request.closeFriend());
    }

    /** Unfriend. 204, idempotent. */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfriend(@CurrentUser Profile caller, @PathVariable UUID userId) {
        friendService.unfriend(caller.getId(), userId);
    }
}
