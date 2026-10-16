package com.pm.filmfuse.social;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V5__social_graph: constraints, cascades on account deletion and ml_reader access. */
class SocialSchemaIntegrationTest extends AbstractSocialIntegrationTest {

    private static final String[] TABLES = {"app.friendships", "app.friend_closeness", "app.blocks", "app.invites"};

    @Test
    void mlReaderCanReadButNotWriteTheSocialTables() {
        for (String table : TABLES) {
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, 'SELECT')", Boolean.class, table))
                    .as("SELECT on %s", table).isTrue();
            for (String write : new String[] {"INSERT", "UPDATE", "DELETE"}) {
                assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, ?)", Boolean.class, table, write))
                        .as("%s on %s", write, table).isFalse();
            }
        }
    }

    @Test
    void deletingAnAccountCascadesEveryRow() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        TestUser pending = user();
        TestUser blocked = user();
        TestUser blocker = user();
        befriend(me, friend);
        sendRequest(me, pending).andExpect(status().isOk());
        block(me, blocked);
        block(blocker, me);
        mockMvc.perform(post("/api/v1/invites").with(me.auth())).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/me").with(me.auth())).andExpect(status().isNoContent());

        assertThat(count("select count(*) from app.friendships where user_low = ? or user_high = ?", me.id(), me.id())).isZero();
        assertThat(count("select count(*) from app.friend_closeness where user_id = ? or friend_id = ?", me.id(), me.id())).isZero();
        assertThat(count("select count(*) from app.blocks where blocker_id = ? or blocked_id = ?", me.id(), me.id())).isZero();
        assertThat(count("select count(*) from app.invites where inviter_id = ?", me.id())).isZero();
        // The others are untouched.
        assertThat(count("select count(*) from app.profiles where id in (?, ?)", friend.id(), blocker.id())).isEqualTo(2);
    }

    @Test
    void friendshipsMustBeACanonicalPairWithTheRequesterInIt() {
        UUID low = profile("00000000-0000-0000-0000-");
        UUID high = profile("ffffffff-0000-0000-0000-");
        UUID outsider = profile("88888888-0000-0000-0000-");
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.friendships (user_low, user_high, status, requested_by) values (?, ?, 'PENDING', ?)",
                high, low, high)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.friendships (user_low, user_high, status, requested_by) values (?, ?, 'PENDING', ?)",
                low, low, low)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.friendships (user_low, user_high, status, requested_by) values (?, ?, 'PENDING', ?)",
                low, high, outsider)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.friendships (user_low, user_high, status, requested_by) values (?, ?, 'MAYBE', ?)",
                low, high, low)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into app.friendships (user_low, user_high, status, requested_by) values (?, ?, 'PENDING', ?)",
                low, high, low);
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.friendships (user_low, user_high, status, requested_by) values (?, ?, 'PENDING', ?)",
                low, high, high)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void closenessScoreBoundsAndNoSelfBlocks() {
        UUID a = profile("11111111-0000-0000-0000-");
        UUID b = profile("22222222-0000-0000-0000-");
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.friend_closeness (user_id, friend_id, score) values (?, ?, 1.5)", a, b))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.friend_closeness (user_id, friend_id, score) values (?, ?, -0.1)", a, b))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into app.friend_closeness (user_id, friend_id) values (?, ?)", a, b);
        assertThat(jdbc.queryForObject("select score from app.friend_closeness where user_id = ?", Float.class, a)).isEqualTo(0.5f);
        assertThat(jdbc.queryForObject("select explicit_close from app.friend_closeness where user_id = ?", Boolean.class, a)).isFalse();
        assertThatThrownBy(() -> jdbc.update("insert into app.blocks (blocker_id, blocked_id) values (?, ?)", a, a))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void profilesHaveANullableEmailAndPrefixSearchIndexes() {
        assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema = 'app' and table_name = 'profiles' and column_name = 'email'""", String.class))
                .isEqualTo("YES");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname = 'app'", String.class))
                .contains("profiles_username_prefix_idx", "profiles_display_name_prefix_idx",
                        "friendships_user_high_idx", "blocks_blocked_idx");
    }

    /** A bare profile whose id starts with {@code idPrefix} (so the test controls uuid order). */
    private UUID profile(String idPrefix) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID id = UUID.fromString(idPrefix + random);
        jdbc.update("insert into app.profiles (id) values (?)", id);
        return id;
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
