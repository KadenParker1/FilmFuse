package com.pm.filmfuse.group.service;

import com.pm.filmfuse.user.service.AccountDeletionEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On account deletion, the user leaves every group the normal way first: groups they own pass to
 * the earliest-joined ACTIVE member, or are deleted when nobody ACTIVE is left. (The foreign-key
 * cascades would otherwise delete groups they own outright.) Session membership and votes simply
 * cascade; their sessions survive with {@code created_by} null.
 */
@Component
public class GroupMembershipCleanup {

    private final GroupService groupService;

    public GroupMembershipCleanup(GroupService groupService) {
        this.groupService = groupService;
    }

    @EventListener
    public void onAccountDeletion(AccountDeletionEvent event) {
        groupService.removeFromAllGroups(event.userId());
    }
}
