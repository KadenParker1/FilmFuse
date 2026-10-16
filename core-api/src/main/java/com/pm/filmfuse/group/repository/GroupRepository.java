package com.pm.filmfuse.group.repository;

import com.pm.filmfuse.common.json.JsonColumns;
import com.pm.filmfuse.group.dto.GroupRole;
import com.pm.filmfuse.group.dto.MemberStatus;
import com.pm.filmfuse.group.model.Group;
import com.pm.filmfuse.group.model.GroupListing;
import com.pm.filmfuse.group.model.GroupMember;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.model.ContentFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * {@code app.groups} and {@code app.group_members}. Plain JDBC, like the social repositories.
 * Membership rules live in GroupService; writers lock the group row first ({@link #lockGroup}).
 */
@Repository
public class GroupRepository {

    private static final String GROUP_COLUMNS = "g.id, g.name, g.emoji, g.color, g.owner_id, g.max_certification, "
            + "g.content_limits::text as content_limits, g.created_at";
    private static final String MEMBER_COLUMNS = "gm.group_id, gm.user_id, gm.role, gm.status, gm.invited_at, gm.joined_at";

    private final NamedParameterJdbcTemplate jdbc;

    public GroupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertGroup(Group group) {
        jdbc.update("""
                insert into app.groups (id, name, emoji, color, owner_id, max_certification, content_limits,
                                        created_at, updated_at)
                values (:id, :name, :emoji, :color, :owner, :max, cast(:limits as jsonb), :now, :now)
                """, new MapSqlParameterSource("id", group.id())
                .addValue("name", group.name())
                .addValue("emoji", group.emoji())
                .addValue("color", group.color())
                .addValue("owner", group.ownerId())
                .addValue("max", label(group.cap().maxCertification()))
                .addValue("limits", JsonColumns.write(group.cap().contentLimitCodes()))
                .addValue("now", Timestamp.from(group.createdAt())));
    }

    /** Inserts each user as a member unless already one (any status); returns how many were added. */
    public int insertMembers(UUID groupId, Collection<UUID> userIds, GroupRole role, MemberStatus status,
                             UUID invitedBy, Instant now) {
        int added = 0;
        for (UUID userId : userIds) {
            added += jdbc.update("""
                    insert into app.group_members (group_id, user_id, role, status, invited_by, invited_at, joined_at)
                    values (:group, :user, :role, :status, :invitedBy, :now, :joinedAt)
                    on conflict (group_id, user_id) do nothing
                    """, new MapSqlParameterSource("group", groupId)
                    .addValue("user", userId)
                    .addValue("role", role.name())
                    .addValue("status", status.name())
                    .addValue("invitedBy", invitedBy)
                    .addValue("now", Timestamp.from(now))
                    .addValue("joinedAt", status == MemberStatus.ACTIVE ? Timestamp.from(now) : null));
        }
        return added;
    }

    public Optional<Group> findGroup(UUID id) {
        return jdbc.query("select %s from app.groups g where g.id = :id".formatted(GROUP_COLUMNS),
                new MapSqlParameterSource("id", id), (rs, i) -> group(rs)).stream().findFirst();
    }

    /** The group row, locked until the transaction ends: serialises membership changes per group. */
    public Optional<Group> lockGroup(UUID id) {
        return jdbc.query("select %s from app.groups g where g.id = :id for update".formatted(GROUP_COLUMNS),
                new MapSqlParameterSource("id", id), (rs, i) -> group(rs)).stream().findFirst();
    }

    public Optional<GroupMember> findMembership(UUID groupId, UUID userId) {
        return jdbc.query("select %s from app.group_members gm where gm.group_id = :group and gm.user_id = :user"
                        .formatted(MEMBER_COLUMNS),
                new MapSqlParameterSource("group", groupId).addValue("user", userId), (rs, i) -> member(rs))
                .stream().findFirst();
    }

    /** ACTIVE first (owner, then earliest joined), then INVITED (earliest invited). */
    public List<GroupMember> findMembers(UUID groupId) {
        return jdbc.query("""
                select %s from app.group_members gm where gm.group_id = :group
                order by case gm.status when 'ACTIVE' then 0 else 1 end,
                         case gm.role when 'OWNER' then 0 else 1 end,
                         gm.joined_at nulls last, gm.invited_at, gm.user_id
                """.formatted(MEMBER_COLUMNS), new MapSqlParameterSource("group", groupId), (rs, i) -> member(rs));
    }

    public int countMembers(UUID groupId) {
        Integer count = jdbc.queryForObject("select count(*) from app.group_members where group_id = :group",
                new MapSqlParameterSource("group", groupId), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * {@code userId}'s groups with their membership, ACTIVE member count and newest session. ACTIVE
     * memberships first by newest session (groups without one last, newest group first), then
     * INVITED ones, most recent invite first.
     */
    public List<GroupListing> listFor(UUID userId) {
        return jdbc.query("""
                select * from (
                    select %s, %s,
                           (select count(*) from app.group_members a
                            where a.group_id = g.id and a.status = 'ACTIVE') as active_count,
                           (select max(s.created_at) from app.rec_sessions s where s.group_id = g.id) as last_session_at
                    from app.group_members gm
                    join app.groups g on g.id = gm.group_id
                    where gm.user_id = :user
                ) listed
                order by case status when 'ACTIVE' then 0 else 1 end,
                         case when status = 'ACTIVE' then last_session_at end desc nulls last,
                         case when status = 'ACTIVE' then created_at end desc,
                         invited_at desc, id
                """.formatted(GROUP_COLUMNS, MEMBER_COLUMNS), new MapSqlParameterSource("user", userId),
                (rs, i) -> new GroupListing(group(rs), member(rs), rs.getInt("active_count"),
                        instant(rs, "last_session_at")));
    }

    /** Up to {@code perGroup} ACTIVE member ids of each group, earliest joined first. */
    public Map<UUID, List<UUID>> activePreview(Collection<UUID> groupIds, int perGroup) {
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        if (groupIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                select group_id, user_id from (
                    select gm.group_id, gm.user_id,
                           row_number() over (partition by gm.group_id order by gm.joined_at, gm.user_id) as n
                    from app.group_members gm
                    where gm.group_id in (:groups) and gm.status = 'ACTIVE'
                ) ranked where n <= :perGroup order by group_id, n
                """, new MapSqlParameterSource("groups", groupIds).addValue("perGroup", perGroup), rs -> {
            result.computeIfAbsent(rs.getObject("group_id", UUID.class), k -> new ArrayList<>())
                    .add(rs.getObject("user_id", UUID.class));
        });
        return result;
    }

    /** Every group {@code userId} belongs to (any status), for account deletion. */
    public List<UUID> groupIdsOf(UUID userId) {
        return jdbc.queryForList("select group_id from app.group_members where user_id = :user order by group_id",
                new MapSqlParameterSource("user", userId), UUID.class);
    }

    public void updateDetails(UUID groupId, String name, String emoji, String color, Instant now) {
        jdbc.update("update app.groups set name = :name, emoji = :emoji, color = :color, updated_at = :now where id = :id",
                new MapSqlParameterSource("id", groupId).addValue("name", name).addValue("emoji", emoji)
                        .addValue("color", color).addValue("now", Timestamp.from(now)));
    }

    public void updateCap(UUID groupId, ContentFilter cap, Instant now) {
        jdbc.update("""
                update app.groups set max_certification = :max, content_limits = cast(:limits as jsonb), updated_at = :now
                where id = :id
                """, new MapSqlParameterSource("id", groupId)
                .addValue("max", label(cap.maxCertification()))
                .addValue("limits", JsonColumns.write(cap.contentLimitCodes()))
                .addValue("now", Timestamp.from(now)));
    }

    public void activate(UUID groupId, UUID userId, Instant now) {
        jdbc.update("""
                update app.group_members set status = 'ACTIVE', joined_at = :now
                where group_id = :group and user_id = :user and status = 'INVITED'
                """, new MapSqlParameterSource("group", groupId).addValue("user", userId)
                .addValue("now", Timestamp.from(now)));
    }

    /** Makes {@code newOwner} (an ACTIVE member) the owner and the old owner a plain member. */
    public void transferOwnership(UUID groupId, UUID newOwner, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource("group", groupId).addValue("owner", newOwner)
                .addValue("now", Timestamp.from(now));
        // Demote first: the one-owner unique index is checked per statement.
        jdbc.update("update app.group_members set role = 'MEMBER' where group_id = :group and role = 'OWNER'", params);
        jdbc.update("update app.group_members set role = 'OWNER' where group_id = :group and user_id = :owner", params);
        jdbc.update("update app.groups set owner_id = :owner, updated_at = :now where id = :group", params);
    }

    public boolean deleteMember(UUID groupId, UUID userId) {
        return jdbc.update("delete from app.group_members where group_id = :group and user_id = :user",
                new MapSqlParameterSource("group", groupId).addValue("user", userId)) > 0;
    }

    public void deleteGroup(UUID groupId) {
        jdbc.update("delete from app.groups where id = :id", new MapSqlParameterSource("id", groupId));
    }

    // ------------------------------------------------------------------------------- mapping

    private static String label(Certification certification) {
        return certification == null ? null : certification.label();
    }

    private static Group group(ResultSet rs) throws SQLException {
        return new Group(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("emoji"),
                rs.getString("color"), rs.getObject("owner_id", UUID.class),
                ContentFilter.cap(Certification.fromLabel(rs.getString("max_certification")),
                        ContentFilter.limitsFromJson(rs.getString("content_limits"))),
                instant(rs, "created_at"));
    }

    private static GroupMember member(ResultSet rs) throws SQLException {
        return new GroupMember(rs.getObject("group_id", UUID.class), rs.getObject("user_id", UUID.class),
                GroupRole.valueOf(rs.getString("role")), MemberStatus.valueOf(rs.getString("status")),
                instant(rs, "invited_at"), instant(rs, "joined_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
