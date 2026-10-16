package com.pm.filmfuse.email;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.SimpleMailMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailConfigTest {

    private final EmailConfig config = new EmailConfig();

    @Test
    void mockIsTheDefault() {
        assertThat(config.emailGateway(new EmailProperties(null, null), provider(null)))
                .isInstanceOf(MockEmailGateway.class);
    }

    @Test
    void smtpNeedsAMailSender() {
        assertThatThrownBy(() -> config.emailGateway(new EmailProperties("smtp", "a@b.c"), provider(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host");
    }

    @Test
    void smtpSendsWithConfiguredFrom() {
        SimpleMailMessage[] sent = new SimpleMailMessage[1];
        JavaMailSender sender = new JavaMailSenderImpl() {
            @Override
            public void send(SimpleMailMessage... messages) {
                sent[0] = messages[0];
            }
        };
        EmailGateway gateway = config.emailGateway(new EmailProperties("smtp", "FilmFuse <hi@filmfuse.app>"), provider(sender));
        assertThat(gateway).isInstanceOf(SmtpEmailGateway.class);

        gateway.send(new EmailMessage("someone@example.com", "Hello", "Body"));

        assertThat(sent[0].getFrom()).isEqualTo("FilmFuse <hi@filmfuse.app>");
        assertThat(sent[0].getTo()).containsExactly("someone@example.com");
    }

    @Test
    void unknownProviderFailsFast() {
        assertThatThrownBy(() -> config.emailGateway(new EmailProperties("carrier-pigeon", null), provider(null)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject() {
                return sender;
            }

            @Override
            public JavaMailSender getIfAvailable() {
                return sender;
            }
        };
    }
}
