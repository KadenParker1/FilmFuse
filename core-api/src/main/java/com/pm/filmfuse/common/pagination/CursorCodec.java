package com.pm.filmfuse.common.pagination;

import com.pm.filmfuse.common.exception.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Opaque keyset cursors: the sort key of the last row on a page, as a list of strings, packed into
 * one URL-safe token. The client only ever echoes it back; anything it cannot decode, or with the
 * wrong number of parts, is a 400 - never a 500.
 *
 * <p>Parts are joined with {@code \u001F} (unit separator), which no username, uuid or number can
 * contain. A cursor is not a secret or a capability: every query still filters by the caller.
 */
public final class CursorCodec {

    public static final String INVALID_CURSOR = "Invalid cursor.";

    private static final String SEPARATOR = "\u001F";
    private static final int MAX_LENGTH = 1024;

    private CursorCodec() {
    }

    public static String encode(List<String> parts) {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("A cursor needs at least one part");
        }
        for (String part : parts) {
            if (part == null || part.contains(SEPARATOR)) {
                throw new IllegalArgumentException("Cursor parts must be non-null and separator-free");
            }
        }
        byte[] raw = String.join(SEPARATOR, parts).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** The parts of {@code cursor}, which must have exactly {@code expectedParts}; 400 otherwise. */
    public static List<String> decode(String cursor, int expectedParts) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_LENGTH) {
            throw new BadRequestException(INVALID_CURSOR);
        }
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(INVALID_CURSOR);
        }
        List<String> parts = List.of(text.split(SEPARATOR, -1));
        if (parts.size() != expectedParts) {
            throw new BadRequestException(INVALID_CURSOR);
        }
        return parts;
    }
}
