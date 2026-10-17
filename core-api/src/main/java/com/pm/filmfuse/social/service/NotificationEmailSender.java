package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.config.AppProperties;
import com.pm.filmfuse.email.EmailGateway;
import com.pm.filmfuse.email.EmailMessage;
import com.pm.filmfuse.social.repository.SocialProfileRepository;
import com.pm.filmfuse.social.repository.SocialProfileRepository.Contact;
import com.pm.filmfuse.user.model.NotificationType;
import com.pm.filmfuse.user.service.NotificationSettingsService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one way a notification email goes out. For each recipient it checks, in this order: the
 * recipient has {@code type} switched on in their notification settings, and has an email
 * address - otherwise they are skipped. A failed send is logged (ids only, never an address) and
 * swallowed, so one bad recipient never stops the others or the change that triggered the email.
 * Callers invoke it after commit ({@code @TransactionalEventListener(AFTER_COMMIT)}) or from a job.
 */
@Component
public class NotificationEmailSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailSender.class);

    private final EmailGateway emailGateway;
    private final SocialProfileRepository profiles;
    private final NotificationSettingsService settings;
    private final String webUrl;

    public NotificationEmailSender(EmailGateway emailGateway, SocialProfileRepository profiles,
                                   NotificationSettingsService settings, AppProperties appProperties) {
        this.emailGateway = emailGateway;
        this.profiles = profiles;
        this.settings = settings;
        this.webUrl = appProperties.webUrl();
    }

    /** The web app origin (no trailing slash), the base of every link in an email. */
    public String webUrl() {
        return webUrl;
    }

    /** "Display Name (@username)" for {@code userId}, or "Someone" for an unknown user. */
    public String nameOf(UUID userId) {
        return profiles.findContact(userId).map(SocialEmailNotifier::nameOf).orElse("Someone");
    }

    /**
     * Sends {@code message(recipient)} to each of {@code recipientIds} that wants {@code type}.
     * {@code message} builds the subject and body; the recipient's address is filled in here.
     *
     * @return how many emails were handed to the gateway
     */
    public int send(Collection<UUID> recipientIds, NotificationType type, Function<UUID, Draft> message) {
        Set<UUID> ids = new LinkedHashSet<>(recipientIds);
        if (ids.isEmpty()) {
            return 0;
        }
        int sent = 0;
        try {
            Set<UUID> allowed = settings.allowing(ids, type);
            Map<UUID, Contact> contacts = profiles.findContacts(allowed);
            for (UUID id : ids) {
                Contact contact = contacts.get(id);
                if (contact == null || contact.email() == null || contact.email().isBlank()) {
                    log.debug("Skipping a {} email to {}: switched off or no address", type, id);
                    continue;
                }
                try {
                    Draft draft = message.apply(id);
                    emailGateway.send(new EmailMessage(contact.email(), draft.subject(), draft.body()));
                    sent++;
                } catch (RuntimeException ex) {
                    log.warn("Could not send a {} email to user {}", type, id, ex);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Could not send {} emails to {} users", type, ids.size(), ex);
        }
        return sent;
    }

    /** {@link #send} to one recipient. */
    public boolean send(UUID recipientId, NotificationType type, Draft draft) {
        return send(Set.of(recipientId), type, id -> draft) == 1;
    }

    /** The subject and body of one email. */
    public record Draft(String subject, String body) {
    }
}
