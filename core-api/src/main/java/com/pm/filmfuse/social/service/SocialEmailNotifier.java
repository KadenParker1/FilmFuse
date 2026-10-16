package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.config.AppProperties;
import com.pm.filmfuse.email.EmailGateway;
import com.pm.filmfuse.email.EmailMessage;
import com.pm.filmfuse.social.repository.SocialProfileRepository;
import com.pm.filmfuse.social.repository.SocialProfileRepository.Contact;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Social notification emails, sent only once the change that triggered them has committed - a
 * rolled-back request never emails anyone. A recipient without a known email address is skipped.
 * A failed send is logged and swallowed: the friend request itself has already succeeded.
 */
@Component
public class SocialEmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(SocialEmailNotifier.class);

    private final EmailGateway emailGateway;
    private final SocialProfileRepository profiles;
    private final String webUrl;

    public SocialEmailNotifier(EmailGateway emailGateway, SocialProfileRepository profiles,
                               AppProperties appProperties) {
        this.emailGateway = emailGateway;
        this.profiles = profiles;
        this.webUrl = appProperties.webUrl();
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
        try {
            Contact recipient = profiles.findContact(recipientId).orElse(null);
            Contact actor = profiles.findContact(actorId).orElse(null);
            if (recipient == null || actor == null || recipient.email() == null || recipient.email().isBlank()) {
                log.debug("Skipping a social email to {}: no recipient address", recipientId);
                return;
            }
            String name = nameOf(actor);
            emailGateway.send(new EmailMessage(recipient.email(), subjectTemplate.formatted(name),
                    bodyTemplate.formatted(name, webUrl + "/user/" + actorId)));
        } catch (RuntimeException ex) {
            // No address in the log: logs outlive account deletion.
            log.warn("Could not send a social email to user {}", recipientId, ex);
        }
    }

    /** "Display Name (@username)", or whichever half exists. */
    static String nameOf(Contact contact) {
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
