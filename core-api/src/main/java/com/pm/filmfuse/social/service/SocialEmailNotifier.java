package com.pm.filmfuse.social.service;

import com.pm.filmfuse.social.repository.SocialProfileRepository.Contact;
import com.pm.filmfuse.social.service.NotificationEmailSender.Draft;
import com.pm.filmfuse.user.model.NotificationType;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Social notification emails, sent only once the change that triggered them has committed - a
 * rolled-back request never emails anyone. Both respect the recipient's {@code friendRequests}
 * preference; a recipient without a known email address is skipped; a failed send is logged and
 * swallowed (see {@link NotificationEmailSender}).
 */
@Component
public class SocialEmailNotifier {

    private final NotificationEmailSender sender;

    public SocialEmailNotifier(NotificationEmailSender sender) {
        this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequestSent(FriendRequestSentEvent event) {
        send(event.recipientId(), event.requesterId(),
                "%s sent you a friend request",
                """
                %s sent you a friend request on FilmFuse.

                Open their profile to accept or decline:
                %s
                """);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequestAccepted(FriendRequestAcceptedEvent event) {
        send(event.requesterId(), event.accepterId(),
                "%s accepted your friend request",
                """
                %s accepted your friend request on FilmFuse. You're friends now.

                See their profile:
                %s
                """);
    }

    private void send(UUID recipientId, UUID actorId, String subjectTemplate, String bodyTemplate) {
        sender.send(java.util.List.of(recipientId), NotificationType.FRIEND_REQUESTS, id -> {
            String name = sender.nameOf(actorId);
            return new Draft(subjectTemplate.formatted(name),
                    bodyTemplate.formatted(name, sender.webUrl() + "/user/" + actorId));
        });
    }

    /** "Display Name (@username)", or whichever half exists. */
    public static String nameOf(Contact contact) {
        String displayName = contact.displayName() == null || contact.displayName().isBlank()
                ? null : contact.displayName().trim();
        String handle = contact.username() == null ? null : "@" + contact.username();
        if (displayName != null && handle != null) {
            return displayName + " (" + handle + ")";
        }
        if (displayName != null) {
            return displayName;
        }
        return handle != null ? handle : "Someone";
    }
}
