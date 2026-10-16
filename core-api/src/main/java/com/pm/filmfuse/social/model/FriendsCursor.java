package com.pm.filmfuse.social.model;

import java.util.UUID;

/**
 * The keyset position in a friends list ordered by explicit-close desc, closeness desc,
 * coalesce(username, '') asc, id asc: the sort key of the last friend on the previous page.
 */
public record FriendsCursor(boolean explicitClose, float closeness, String sortName, UUID id) {
}
