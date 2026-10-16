package com.pm.filmfuse.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends over SMTP under {@code filmfuse.email.provider=smtp}: Resend in prod
 * ({@code smtp.resend.com:2587}), Mailpit locally. Failures ({@code MailException}, unchecked)
 * propagate so each caller can decide whether a failed send should fail its request.
 */
public class SmtpEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailGateway.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailGateway(JavaMailSender mailSender, String fromAddress) {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException("filmfuse.email.from-address must be set to send over SMTP");
        }
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(fromAddress);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        mailSender.send(mail);
        log.info("[smtp] sent one email");
    }
}
