package com.pm.filmfuse.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailMessageTest {

    @Test
    void controlCharactersInTheSubjectCannotStartANewHeader() {
        // A group or display name chosen to smuggle a Bcc header into the invite email.
        EmailMessage message = new EmailMessage("to@example.com",
                "Ana invited you to Movie night\r\nBcc: victim@example.com\u0000", "Body\nkeeps\nnewlines");
        assertThat(message.subject()).isEqualTo("Ana invited you to Movie night Bcc: victim@example.com");
        assertThat(message.subject()).doesNotContain("\r", "\n");
        assertThat(message.body()).isEqualTo("Body\nkeeps\nnewlines");
    }

    @Test
    void aSubjectOfOnlyControlCharactersIsRefused() {
        assertThatThrownBy(() -> new EmailMessage("to@example.com", "\r\n", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRecipientWithALineBreakIsRefused() {
        assertThatThrownBy(() -> new EmailMessage("to@example.com\r\nBcc: x@example.com", "Hi", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
