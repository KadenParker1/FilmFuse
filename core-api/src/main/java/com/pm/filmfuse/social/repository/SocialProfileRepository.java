package com.pm.filmfuse.social.repository;

import com.pm.filmfuse.social.model.SocialProfile;
import com.pm.filmfuse.social.model.SocialUser;
import com.pm.filmfuse.user.model.AccountStatus;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to {@code app.profiles} for the social module: summaries, the public profile,
 * people search and notification recipients. Graph rules (blocks, friendships) are applied by the
 * callers, through SocialGraphService.
 */
@Repository
public class SocialProfileRepository {

    /** Who to email, and how to name the other person in it. */
    public record Contact(UUID id, String username, String displayName, String email) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SocialProfileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SocialProfile> findProfile(UUID id) {
        return jdbc.query("select %s, p.bio, p.status from app.profiles p where p.id = :id"
                                .formatted(SocialRows.USER_COLUMNS), new MapSqlParameterSource("id", id),
                        (rs, i) -> new SocialProfile(SocialRows.user(rs), rs.getString("bio"),
                                AccountStatus.valueOf(rs.getString("status"))))
                .stream().findFirst();
    }

    /** The users among {@code ids} that exist, keyed by id (iteration order unspecified). */
    public Map<UUID, SocialUser> findUsers(Collection<UUID> ids) {
        Map<UUID, SocialUser> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.query("select %s from app.profiles p where p.id in (:ids)".formatted(SocialRows.USER_COLUMNS),
                new MapSqlParameterSource("ids", ids), rs -> {
                    SocialUser user = SocialRows.user(rs);
                    result.put(user.id(), user);
                });
        return result;
    }

    public Optional<Contact> findContact(UUID id) {
        return jdbc.query("select id, username, display_name, email from app.profiles where id = :id",
                        new MapSqlParameterSource("id", id),
                        (rs, i) -> new Contact(rs.getObject("id", UUID.class), rs.getString("username"),
                                rs.getString("display_name"), rs.getString("email")))
                .stream().findFirst();
    }

    /** Contacts of the users among {@code ids} that exist, keyed by id. */
    public Map<UUID, Contact> findContacts(Collection<UUID> ids) {
        Map<UUID, Contact> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.query("select id, username, display_name, email from app.profiles where id in (:ids)",
                new MapSqlParameterSource("ids", ids), rs -> {
                    Contact contact = new Contact(rs.getObject("id", UUID.class), rs.getString("username"),
                            rs.getString("display_name"), rs.getString("email"));
                    result.put(contact.id(), contact);
                });
        return result;
    }

    /**
     * Active users with a username whose username or display name starts with {@code prefix}
     * (already lowercased and LIKE-escaped by the caller), excluding {@code excluded}, ordered by
     * username and starting after {@code afterUsername} (null = from the start).
     */
    public List<SocialUser> search(String escapedLowerPrefix, Collection<UUID> excluded, String afterUsername,
                                   int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("pattern", escapedLowerPrefix + "%")
                .addValue("excluded", excluded)
                .addValue("limit", limit);
        StringBuilder sql = new StringBuilder("""
                select %s from app.profiles p
                where p.username is not null
                  and p.status <> 'SUSPENDED'
                  and (p.username like :pattern escape '\\' or lower(p.display_name) like :pattern escape '\\')
                  and p.id not in (:excluded)
                """.formatted(SocialRows.USER_COLUMNS));
        if (afterUsername != null) {
            sql.append(" and p.username > :after");
            params.addValue("after", afterUsername);
        }
        sql.append(" order by p.username limit :limit");
        return jdbc.query(sql.toString(), params, (rs, i) -> SocialRows.user(rs));
    }
}
