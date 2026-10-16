package com.pm.filmfuse.email;

/** One outbound plain-text message. Templates come later. */
public record EmailMessage(String to, String subject, String body) {

    public EmailMessage {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("An email needs a recipient");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("An email needs a subject");
        }
    }
}
