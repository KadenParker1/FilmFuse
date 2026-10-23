package com.pm.filmfuse.group.controller;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.group.dto.AddMembersDTO;
import com.pm.filmfuse.group.dto.CastVoteDTO;
import com.pm.filmfuse.group.dto.CreateSessionDTO;
import com.pm.filmfuse.group.dto.PickMovieDTO;
import com.pm.filmfuse.group.dto.SessionDTO;
import com.pm.filmfuse.group.dto.SessionListStatus;
import com.pm.filmfuse.group.dto.SessionSummaryDTO;
import com.pm.filmfuse.group.service.SessionService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Recommendation sessions. Members only (404 otherwise); see {@link SessionService}. */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 201, with up to 20 results. Exactly one of groupId / memberIds (400 otherwise). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDTO create(@CurrentUser Profile caller, @Valid @RequestBody CreateSessionDTO request) {
        return sessionService.create(caller.getId(), request);
    }

    /**
     * The caller's sessions, newest first, {@code limit} (1-50, default 20) per page; only a
     * group's with {@code groupId}, only OPEN ("Live now") or CLOSED (history) with {@code status}.
     */
    @GetMapping
    public PageDTO<SessionSummaryDTO> list(
            @CurrentUser Profile caller,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) SessionListStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(value = 1, message = "Must be between 1 and 50")
            @Max(value = 50, message = "Must be between 1 and 50") Integer limit) {
        return sessionService.list(caller.getId(), groupId, status, cursor, limit);
    }

    /** 404 for non-members; 403 for an ACTIVE group member not in this OPEN group session yet (they may join). */
    @GetMapping("/{sessionId}")
    public SessionDTO get(@CurrentUser Profile caller, @PathVariable UUID sessionId) {
        return sessionService.get(caller.getId(), sessionId);
    }

    /** Casts or replaces the caller's vote. 409 unless OPEN; 404 if the movie is not a result. */
    @PutMapping("/{sessionId}/votes/{movieId}")
    public SessionDTO vote(@CurrentUser Profile caller, @PathVariable UUID sessionId, @PathVariable int movieId,
                           @Valid @RequestBody CastVoteDTO request) {
        return sessionService.vote(caller.getId(), sessionId, movieId, request.vote());
    }

    /** Withdraws the caller's vote (idempotent). 409 unless OPEN; 404 if the movie is not a result. */
    @DeleteMapping("/{sessionId}/votes/{movieId}")
    public SessionDTO clearVote(@CurrentUser Profile caller, @PathVariable UUID sessionId, @PathVariable int movieId) {
        return sessionService.clearVote(caller.getId(), sessionId, movieId);
    }

    /** Appends new results (never one already shown). 409 unless OPEN. */
    @PostMapping("/{sessionId}/refresh")
    public SessionDTO refresh(@CurrentUser Profile caller, @PathVariable UUID sessionId) {
        return sessionService.refresh(caller.getId(), sessionId);
    }

    /** Decides the session. 409 unless OPEN; 400 if the movie is vetoed or not a result. */
    @PostMapping("/{sessionId}/pick")
    public SessionDTO pick(@CurrentUser Profile caller, @PathVariable UUID sessionId,
                           @Valid @RequestBody PickMovieDTO request) {
        return sessionService.pick(caller.getId(), sessionId, request.movieId());
    }

    /** Any member adds their own friends (400 otherwise, blocks included). 409 unless OPEN or past 20 people. */
    @PostMapping("/{sessionId}/members")
    public SessionDTO addMembers(@CurrentUser Profile caller, @PathVariable UUID sessionId,
                                 @Valid @RequestBody AddMembersDTO request) {
        return sessionService.addMembers(caller.getId(), sessionId, request.userIds());
    }

    /** An ACTIVE member of the session's group joins it (idempotent). 404 otherwise; 409 unless OPEN or when full. */
    @PostMapping("/{sessionId}/join")
    public SessionDTO join(@CurrentUser Profile caller, @PathVariable UUID sessionId) {
        return sessionService.join(caller.getId(), sessionId);
    }

    /** The creator ends it without a pick (403 for other members). 409 unless OPEN. */
    @PostMapping("/{sessionId}/end")
    public SessionDTO end(@CurrentUser Profile caller, @PathVariable UUID sessionId) {
        return sessionService.end(caller.getId(), sessionId);
    }
}
