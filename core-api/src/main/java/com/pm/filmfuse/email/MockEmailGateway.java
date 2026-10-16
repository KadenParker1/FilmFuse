package com.pm.filmfuse.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used under {@code filmfuse.email.provider=mock} (the default, and always in tests). Sends nothing. */
public class MockEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(MockEmailGateway.class);

    @Override
    public void send(EmailMessage message) {
        // No recipient address in logs - logs live outside account deletion.
        log.info("[mock email] would send one email (subject length {})", message.subject().length());
    }
}
