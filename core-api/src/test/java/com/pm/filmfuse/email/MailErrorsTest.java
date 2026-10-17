package com.pm.filmfuse.email;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThat;

class MailErrorsTest {

    @Test
    void redactsEveryAddressShape() {
        assertThat(MailErrors.redact("to a@b.io, <First.Last+x@mail.example.co.uk> and \"o'neil@x-y.org\""))
                .isEqualTo("to <redacted-email>, <<redacted-email>> and \"<redacted-email>\"");
        assertThat(MailErrors.redact("no address here, just @handles and user@localhost"))
                .isEqualTo("no address here, just @handles and user@localhost");
        assertThat(MailErrors.redact(null)).isNull();
    }

    @Test
    void describesClassAndRedactedMessageOfTheExceptionAndItsCauses() {
        Exception ex = new MailSendException("550 rejected <bob@example.com>",
                new IllegalStateException("for bob@example.com\r\nand more"));
        assertThat(MailErrors.describe(ex)).isEqualTo(
                "org.springframework.mail.MailSendException: 550 rejected <<redacted-email>>; "
                        + "caused by java.lang.IllegalStateException: for <redacted-email> and more");
        assertThat(MailErrors.describe(new RuntimeException())).isEqualTo("java.lang.RuntimeException");
    }

    @Test
    void capsLongMessagesAndDeepCauseChains() {
        Throwable deep = new RuntimeException("x".repeat(1_000));
        for (int i = 0; i < 10; i++) {
            deep = new RuntimeException("level " + i, deep);
        }
        String described = MailErrors.describe(deep);
        assertThat(described.split("caused by")).hasSize(5);
        assertThat(MailErrors.describe(new RuntimeException("y".repeat(1_000)))).hasSizeLessThan(400);
    }
}
