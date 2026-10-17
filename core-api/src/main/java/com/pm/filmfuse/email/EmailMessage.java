package com.pm.filmfuse.email;

/**
 * One outbound plain-text message. Templates come later.
 *
 * <p>Subjects embed user-controlled text (display names, group names, movie titles), so every
 * control character - CR and LF above all - becomes a space: a header value must never be able to
 * start a new header line. The body is plain text and keeps its newlines.
 */
public record EmailMessage(String to, String subject, String body) {

    public EmailMessage {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("An email needs a recipient");
        }
        if (to.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("An email recipient cannot contain control characters");
        }
        subject = subject == null ? null : subject.replaceAll("\\p{Cntrl}+", " ").strip();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("An email needs a subject");
        }
    }
}
