package com.pm.filmfuse.group.service;

import com.pm.filmfuse.social.service.NotificationEmailSender;
import com.pm.filmfuse.social.service.NotificationEmailSender.Draft;
import com.pm.filmfuse.user.model.NotificationType;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Group invite and "session started" emails, sent after commit and only to recipients whose
 * {@code groupInvites} / {@code sessionStarted} preference is on (see {@link NotificationEmailSender}).
 */
@Component
public class GroupEmailNotifier {

    private final NotificationEmailSender sender;

    public GroupEmailNotifier(NotificationEmailSender sender) {
        this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvitesSent(GroupInvitesSentEvent event) {
        String name = sender.nameOf(event.inviterId());
        Draft draft = new Draft("%s invited you to %s".formatted(name, event.groupName()), """
                %s invited you to the group "%s" on FilmFuse.

                Open the group to join or decline:
                %s
                """.formatted(name, event.groupName(), sender.webUrl() + "/groups/" + event.groupId()));
        sender.send(event.inviteeIds(), NotificationType.GROUP_INVITES, id -> draft);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionStarted(SessionStartedEvent event) {
        List<java.util.UUID> recipients = event.memberIds().stream()
                .filter(id -> !id.equals(event.creatorId()))
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        String name = sender.nameOf(event.creatorId());
        String where = event.groupName() == null ? "" : " in " + event.groupName();
        Draft draft = new Draft("%s started a movie session".formatted(name), """
                %s started a movie session%s on FilmFuse. Come vote on what to watch:
                %s
                """.formatted(name, where, sender.webUrl() + "/sessions/" + event.sessionId()));
        sender.send(recipients, NotificationType.SESSION_STARTED, id -> draft);
    }
}
