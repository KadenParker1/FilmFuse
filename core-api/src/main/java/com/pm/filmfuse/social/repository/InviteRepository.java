package com.pm.filmfuse.social.repository;

import com.pm.filmfuse.social.model.Invite;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.invites}, looked up by token hash. */
@Repository
public class InviteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public InviteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Invite invite, byte[] tokenHash) {
        jdbc.update("""
                insert into app.invites (id, token_hash, inviter_id, created_at, expires_at)
                values (:id, :hash, :inviter, :createdAt, :expiresAt)
                """, new MapSqlParameterSource("id", invite.id())
                .addValue("hash", tokenHash)
                .addValue("inviter", invite.inviterId())
                .addValue("createdAt", Timestamp.from(invite.createdAt()))
                .addValue("expiresAt", Timestamp.from(invite.expiresAt())));
    }

    public Optional<Invite> findByTokenHash(byte[] tokenHash) {
        return jdbc.query("""
                        select id, inviter_id, created_at, expires_at from app.invites where token_hash = :hash
                        """, new MapSqlParameterSource("hash", tokenHash),
                        (rs, i) -> new Invite(rs.getObject("id", UUID.class), rs.getObject("inviter_id", UUID.class),
                                SocialRows.instant(rs, "created_at"), SocialRows.instant(rs, "expires_at")))
                .stream().findFirst();
    }
}
