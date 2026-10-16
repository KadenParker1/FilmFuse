package com.pm.filmfuse.email;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** Picks the {@link EmailGateway} adapter from {@code filmfuse.email.provider} (default mock). */
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailConfig {

    /**
     * {@code JavaMailSender} is an {@link ObjectProvider} because Spring Boot only auto-configures
     * one when {@code spring.mail.host} is set; the mock profile must start without a mail server.
     */
    @Bean
    public EmailGateway emailGateway(EmailProperties properties, ObjectProvider<JavaMailSender> mailSender) {
        String provider = properties.provider() == null ? EmailProperties.MOCK_PROVIDER : properties.provider();
        if (EmailProperties.MOCK_PROVIDER.equalsIgnoreCase(provider)) {
            return new MockEmailGateway();
        }
        if (EmailProperties.SMTP_PROVIDER.equalsIgnoreCase(provider)) {
            JavaMailSender sender = mailSender.getIfAvailable();
            if (sender == null) {
                throw new IllegalStateException("filmfuse.email.provider=smtp needs spring.mail.host set"
                        + " (smtp.resend.com in prod, 127.0.0.1:54325 for local Mailpit).");
            }
            return new SmtpEmailGateway(sender, properties.fromAddress());
        }
        throw new IllegalStateException("Unknown filmfuse.email.provider '" + provider
                + "'. Supported: mock, smtp.");
    }
}
