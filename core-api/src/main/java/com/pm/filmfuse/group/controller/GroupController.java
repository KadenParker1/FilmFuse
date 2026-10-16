package com.pm.filmfuse.group.controller;

import com.pm.filmfuse.group.dto.AddMembersDTO;
import com.pm.filmfuse.group.dto.CreateGroupDTO;
import com.pm.filmfuse.group.dto.GroupDetailDTO;
import com.pm.filmfuse.group.dto.GroupSummaryDTO;
import com.pm.filmfuse.group.dto.UpdateGroupDTO;
import com.pm.filmfuse.group.service.GroupService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Saved groups. Non-members get 404 for every group route; see {@link GroupService} for the rules. */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /** 201. The caller is the OWNER; {@code memberIds} are invited (400 unless all are friends). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupDetailDTO create(@CurrentUser Profile caller, @Valid @RequestBody CreateGroupDTO request) {
        return groupService.create(caller.getId(), request);
    }

    /** ACTIVE memberships first (most recent session first), then pending invites. */
    @GetMapping
    public List<GroupSummaryDTO> list(@CurrentUser Profile caller) {
        return groupService.list(caller.getId());
    }

    @GetMapping("/{groupId}")
    public GroupDetailDTO get(@CurrentUser Profile caller, @PathVariable UUID groupId) {
        return groupService.get(caller.getId(), groupId);
    }

    /** Owner only (403); null fields are unchanged. */
    @PatchMapping("/{groupId}")
    public GroupDetailDTO update(@CurrentUser Profile caller, @PathVariable UUID groupId,
                                 @Valid @RequestBody UpdateGroupDTO request) {
        return groupService.update(caller.getId(), groupId, request);
    }

    /** Owner only (403). 204. */
    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser Profile caller, @PathVariable UUID groupId) {
        groupService.delete(caller.getId(), groupId);
    }

    /** Any ACTIVE member invites their own friends (409 past 20 members). */
    @PostMapping("/{groupId}/members")
    public GroupDetailDTO addMembers(@CurrentUser Profile caller, @PathVariable UUID groupId,
                                     @Valid @RequestBody AddMembersDTO request) {
        return groupService.addMembers(caller.getId(), groupId, request);
    }

    /** The owner removes anyone else; anyone removes themselves (leave). 204. */
    @DeleteMapping("/{groupId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@CurrentUser Profile caller, @PathVariable UUID groupId, @PathVariable UUID userId) {
        groupService.removeMember(caller.getId(), groupId, userId);
    }

    /** Idempotent for someone already ACTIVE. */
    @PostMapping("/{groupId}/invites/accept")
    public GroupDetailDTO accept(@CurrentUser Profile caller, @PathVariable UUID groupId) {
        return groupService.acceptInvite(caller.getId(), groupId);
    }

    /** Removes the invite; 409 for someone already ACTIVE (they leave instead). 204. */
    @PostMapping("/{groupId}/invites/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@CurrentUser Profile caller, @PathVariable UUID groupId) {
        groupService.declineInvite(caller.getId(), groupId);
    }
}
