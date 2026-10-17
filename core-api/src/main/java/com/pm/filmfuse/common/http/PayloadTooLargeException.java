package com.pm.filmfuse.common.http;

import java.io.IOException;

/**
 * A request body passed {@code filmfuse.http.max-body-size} while being read. An
 * {@link IOException} because it surfaces from the input stream; JSON converters wrap it, so
 * {@code GlobalExceptionHandler} looks for it in the cause chain and answers 413.
 */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long maxBytes) {
        super("Request body exceeds " + maxBytes + " bytes");
    }

    /** Whether {@code ex} or any of its causes is a {@link PayloadTooLargeException}. */
    public static boolean isCause(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PayloadTooLargeException) {
                return true;
            }
        }
        return false;
    }
}
