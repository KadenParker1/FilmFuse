package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.config.AppProperties;
import com.pm.filmfuse.email.EmailGateway;
import com.pm.filmfuse.social.repository.SocialProfileRepository;
import com.pm.filmfuse.social.repository.SocialProfileRepository.Contact;
import com.pm.filmfuse.user.model.NotificationType;
import com.pm.filmfuse.user.service.NotificationSettingsService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SECURITY.md #18: a failed send is logged with the user id and no recipient address. */
@ExtendWith(OutputCaptureExtension.class)
class NotificationEmailSenderLoggingTest {

    private static final String ADDRESS = "victim.person+tag@example.co.uk";

    private final EmailGateway gateway = mock(EmailGateway.class);
    private final SocialProfileRepository profiles = mock(SocialProfileRepository.class);
    private final NotificationSettingsService settings = mock(NotificationSettingsService.class);
    private final NotificationEmailSender sender =
            new NotificationEmailSender(gateway, profiles, settings, new AppProperties("https://filmfuse.test"));

    @Test
    void aFailedSendLogsTheUserIdAndExceptionClassButNotTheAddress(CapturedOutput output) {
        UUID failing = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        when(settings.allowing(anyCollection(), eq(NotificationType.FRIEND_REQUESTS))).thenReturn(Set.of(failing, fine));
        when(profiles.findContacts(anyCollection())).thenReturn(Map.of(
                failing, new Contact(failing, "f", "F", ADDRESS),
                fine, new Contact(fine, "g", "G", "fine@example.com")));
        doThrow(new MailSendException("Failed messages: 550 5.1.1 <" + ADDRESS + ">: Recipient address rejected",
                new IllegalStateException("SMTP said no to " + ADDRESS)))
                .when(gateway).send(any());

        int sent = sender.send(List.of(failing), NotificationType.FRIEND_REQUESTS,
                id -> new NotificationEmailSender.Draft("Hi", "Body"));

        assertThat(sent).isZero();
        String logs = output.getAll();
        assertThat(logs).contains("Could not send a FRIEND_REQUESTS email to user " + failing)
                .contains("org.springframework.mail.MailSendException")
                .contains("java.lang.IllegalStateException")
                .contains("<redacted-email>")
                .doesNotContain(ADDRESS)
                .doesNotContain("example.co.uk")
                // No stack trace: that would repeat the message (and the address) verbatim.
                .doesNotContain("\tat ");
    }

    @Test
    void aFailureBeforeSendingIsAlsoLoggedWithoutAddresses(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        when(settings.allowing(anyCollection(), any())).thenThrow(new RuntimeException("lookup failed for " + ADDRESS));

        assertThat(sender.send(List.of(id), NotificationType.COMMENTS, x -> null)).isZero();

        assertThat(output.getAll()).contains("Could not send COMMENTS emails to 1 users")
                .contains("java.lang.RuntimeException: lookup failed for <redacted-email>")
                .doesNotContain(ADDRESS);
    }
}
