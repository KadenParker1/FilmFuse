package com.pm.filmfuse.social.repository;

import com.pm.filmfuse.social.model.SocialUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Shared row-mapping helpers for the social repositories. */
final class SocialRows {

    /** The profile columns every {@link SocialUser} query selects, aliased {@code p}. */
    static final String USER_COLUMNS = "p.id, p.username, p.display_name, p.profile_image_url";

    private SocialRows() {
    }

    static SocialUser user(ResultSet rs) throws SQLException {
        return new SocialUser(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("display_name"), rs.getString("profile_image_url"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
