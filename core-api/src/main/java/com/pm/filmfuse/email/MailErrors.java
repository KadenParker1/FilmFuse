package com.pm.filmfuse.email;

import java.util.regex.Pattern;

/**
 * A log-safe description of a failed send. Mail providers (and JavaMail) often put the recipient
 * address in the exception message, and a logged stack trace would carry it into the logs, so
 * callers log {@link #describe} - the exception class plus its message with anything that looks
 * like an email address replaced - instead of the exception itself (SECURITY.md #18).
 */
public final class MailErrors {

    /** Deliberately loose: anything shaped like {@code local@domain.tld}, including in brackets. */
    static final Pattern EMAIL = Pattern.compile("[\\w.!#$%&'*+/=?^`{|}~-]+@[\\w-]+(?:\\.[\\w-]+)+");
    static final String REDACTED = "<redacted-email>";
    private static final int MAX_DEPTH = 5;
    private static final int MAX_MESSAGE = 300;

    private MailErrors() {
    }

    /** Message text with email addresses replaced; null stays null. */
    public static String redact(String text) {
        return text == null ? null : EMAIL.matcher(text).replaceAll(REDACTED);
    }

    /**
     * {@code "SomeException: redacted message; caused by OtherException: ..."} for {@code ex} and up
     * to a few of its causes. Never includes a stack trace.
     */
    public static String describe(Throwable ex) {
        StringBuilder out = new StringBuilder();
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (depth > 0) {
                out.append("; caused by ");
            }
            out.append(current.getClass().getName());
            String message = redact(current.getMessage());
            if (message != null && !message.isBlank()) {
                String trimmed = message.strip().replaceAll("[\\r\\n]+", " ");
                out.append(": ").append(trimmed.length() > MAX_MESSAGE ? trimmed.substring(0, MAX_MESSAGE) + "…" : trimmed);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return out.toString();
    }
}
