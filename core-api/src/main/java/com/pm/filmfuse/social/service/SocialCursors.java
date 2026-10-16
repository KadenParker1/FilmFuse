package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.pagination.CursorCodec;
import com.pm.filmfuse.social.model.FriendEdge;
import com.pm.filmfuse.social.model.FriendsCursor;
import java.util.List;
import java.util.UUID;

/** The social module's keyset cursors, built on {@link CursorCodec}. */
final class SocialCursors {

    private SocialCursors() {
    }

    static String encodeFriends(FriendEdge last) {
        String sortName = last.friend().username() == null ? "" : last.friend().username();
        return CursorCodec.encode(List.of(last.explicitClose() ? "1" : "0", Float.toString(last.closeness()),
                sortName, last.friend().id().toString()));
    }

    static FriendsCursor decodeFriends(String cursor) {
        List<String> parts = CursorCodec.decode(cursor, 4);
        try {
            boolean explicit = switch (parts.get(0)) {
                case "1" -> true;
                case "0" -> false;
                default -> throw new BadRequestException(CursorCodec.INVALID_CURSOR);
            };
            float closeness = Float.parseFloat(parts.get(1));
            if (!(closeness >= 0 && closeness <= 1)) {
                throw new BadRequestException(CursorCodec.INVALID_CURSOR);
            }
            return new FriendsCursor(explicit, closeness, parts.get(2), UUID.fromString(parts.get(3)));
        } catch (IllegalArgumentException ex) {
            // NumberFormatException is one too.
            throw new BadRequestException(CursorCodec.INVALID_CURSOR);
        }
    }

    static String encodeSearch(String lastUsername) {
        return CursorCodec.encode(List.of(lastUsername));
    }

    static String decodeSearch(String cursor) {
        String username = CursorCodec.decode(cursor, 1).getFirst();
        if (username.isEmpty()) {
            throw new BadRequestException(CursorCodec.INVALID_CURSOR);
        }
        return username;
    }
}
