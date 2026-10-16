package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.pagination.CursorCodec;
import com.pm.filmfuse.social.mapper.SocialMapper;
import com.pm.filmfuse.social.model.FriendEdge;
import com.pm.filmfuse.social.model.FriendsCursor;
import com.pm.filmfuse.social.model.SocialUser;
import com.pm.filmfuse.social.repository.SocialProfileRepository.Contact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure helpers of the social services. */
class SocialHelpersTest {

    @Test
    void friendsCursorRoundTrips() {
        UUID id = UUID.randomUUID();
        FriendEdge edge = new FriendEdge(new SocialUser(id, "zoe", null, null), true, 0.8f, Instant.now());
        assertThat(SocialCursors.decodeFriends(SocialCursors.encodeFriends(edge)))
                .isEqualTo(new FriendsCursor(true, 0.8f, "zoe", id));

        FriendEdge nameless = new FriendEdge(new SocialUser(id, null, null, null), false, 0.123456f, Instant.now());
        assertThat(SocialCursors.decodeFriends(SocialCursors.encodeFriends(nameless)))
                .isEqualTo(new FriendsCursor(false, 0.123456f, "", id));
    }

    @Test
    void friendsCursorRejectsTamperedParts() {
        String id = UUID.randomUUID().toString();
        for (List<String> parts : List.of(
                List.of("2", "0.5", "x", id),
                List.of("1", "abc", "x", id),
                List.of("1", "1.5", "x", id),
                List.of("1", "NaN", "x", id),
                List.of("1", "0.5", "x", "not-a-uuid"))) {
            assertThatThrownBy(() -> SocialCursors.decodeFriends(CursorCodec.encode(parts)))
                    .isInstanceOf(BadRequestException.class).hasMessage("Invalid cursor.");
        }
    }

    @Test
    void searchCursorRoundTripsAndRejectsEmpty() {
        assertThat(SocialCursors.decodeSearch(SocialCursors.encodeSearch("film_fan"))).isEqualTo("film_fan");
        assertThatThrownBy(() -> SocialCursors.decodeSearch(CursorCodec.encode(List.of(""))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void escapeLikeMakesWildcardsLiteral() {
        assertThat(UserSearchService.escapeLike("a_b%c\\d")).isEqualTo("a\\_b\\%c\\\\d");
        assertThat(UserSearchService.escapeLike("plain")).isEqualTo("plain");
    }

    @Test
    void emailNamesPreferDisplayNameAndHandle() {
        UUID id = UUID.randomUUID();
        assertThat(SocialEmailNotifier.nameOf(new Contact(id, "ann", "Ann Lee", null))).isEqualTo("Ann Lee (@ann)");
        assertThat(SocialEmailNotifier.nameOf(new Contact(id, "ann", "  ", null))).isEqualTo("@ann");
        assertThat(SocialEmailNotifier.nameOf(new Contact(id, null, "Ann Lee", null))).isEqualTo("Ann Lee");
        assertThat(SocialEmailNotifier.nameOf(new Contact(id, null, null, null))).isEqualTo("Someone");
    }

    @Test
    void inviteHashIsSha256OfTheToken() throws Exception {
        byte[] expected = MessageDigest.getInstance("SHA-256").digest("tok".getBytes(StandardCharsets.UTF_8));
        assertThat(InviteService.hash("tok")).isEqualTo(expected).hasSize(32);
    }

    @Test
    void closenessConvertsWithoutFloatNoise() {
        assertThat(SocialMapper.toDouble(0.8f)).isEqualTo(0.8);
        assertThat(SocialMapper.toDouble(0.5f)).isEqualTo(0.5);
        assertThat((double) 0.8f).isNotEqualTo(0.8);
    }
}
