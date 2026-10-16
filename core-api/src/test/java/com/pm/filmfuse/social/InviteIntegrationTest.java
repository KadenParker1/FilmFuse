package com.pm.filmfuse.social;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /invites and POST /invites/{token}/redeem. */
class InviteIntegrationTest extends AbstractSocialIntegrationTest {

    @Test
    void createReturnsATokenLinkAndFourteenDayExpiry() throws Exception {
        TestUser me = user();
        String body = json(mockMvc.perform(post("/api/v1/invites").with(me.auth())).andExpect(status().isOk()));
        String token = JsonPath.read(body, "$.token");
        String url = JsonPath.read(body, "$.url");
        Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(url).isEqualTo("http://localhost:8081/invite/" + token);
        assertThat(Duration.between(Instant.now(), expiresAt).toMinutes())
                .isCloseTo(Duration.ofDays(14).toMinutes(), within(2L));

        // Each call makes a fresh link.
        String other = JsonPath.read(json(mockMvc.perform(post("/api/v1/invites").with(me.auth()))), "$.token");
        assertThat(other).isNotEqualTo(token);
    }

    @Test
    void onlyTheSha256OfTheTokenIsStored() throws Exception {
        TestUser me = user();
        String token = createInvite(me);
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));

        byte[] stored = jdbc.queryForObject("select token_hash from app.invites where inviter_id = ?", byte[].class, me.id());
        assertThat(stored).isEqualTo(expected);
        Integer containsToken = jdbc.queryForObject("""
                select count(*) from app.invites
                where inviter_id = ? and (encode(token_hash, 'escape') like ? or encode(token_hash, 'base64') like ?)
                """, Integer.class, me.id(), "%" + token + "%", "%" + token + "%");
        assertThat(containsToken).isZero();
    }

    @Test
    void redeemMakesFriendsImmediatelyAndTheLinkIsReusable() throws Exception {
        TestUser inviter = user(uniquePrefix(), "Inviter Ivy");
        TestUser first = userWithoutUsername();
        TestUser second = user();
        String token = createInvite(inviter);

        redeem(first, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviter.id").value(inviter.id().toString()))
                .andExpect(jsonPath("$.inviter.username").value(inviter.username()))
                .andExpect(jsonPath("$.inviter.displayName").value("Inviter Ivy"))
                .andExpect(jsonPath("$.inviter.friendship").value("FRIENDS"));
        redeem(second, token).andExpect(status().isOk()).andExpect(jsonPath("$.inviter.friendship").value("FRIENDS"));

        assertThat(stateOf(first, inviter)).isEqualTo("FRIENDS");
        assertThat(stateOf(inviter, second)).isEqualTo("FRIENDS");
        assertThat(closenessRows(inviter, first)).isEqualTo(2);
        assertThat(closenessRows(inviter, second)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select requested_by from app.friendships
                where (user_low = ? and user_high = ?) or (user_low = ? and user_high = ?)""",
                java.util.UUID.class, inviter.id(), first.id(), first.id(), inviter.id())).isEqualTo(inviter.id());

        // Redeeming again when already friends is fine.
        redeem(first, token).andExpect(status().isOk()).andExpect(jsonPath("$.inviter.friendship").value("FRIENDS"));
    }

    @Test
    void redeemResolvesAPendingRequestEitherWay() throws Exception {
        TestUser inviter = user();
        TestUser asked = user();
        TestUser wasAsked = user();
        sendRequest(asked, inviter).andExpect(status().isOk());
        sendRequest(inviter, wasAsked).andExpect(status().isOk());
        String token = createInvite(inviter);

        redeem(asked, token).andExpect(status().isOk());
        redeem(wasAsked, token).andExpect(status().isOk());

        assertThat(stateOf(asked, inviter)).isEqualTo("FRIENDS");
        assertThat(stateOf(wasAsked, inviter)).isEqualTo("FRIENDS");
        assertThat(friendshipRows(asked, inviter)).isEqualTo(1);
        assertThat(closenessRows(wasAsked, inviter)).isEqualTo(2);
    }

    @Test
    void redeemingYourOwnInviteIs400() throws Exception {
        TestUser me = user();
        redeem(me, createInvite(me))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't redeem your own invite."));
    }

    @Test
    void expiredInviteIs410() throws Exception {
        TestUser inviter = user();
        TestUser me = user();
        String token = createInvite(inviter);

        CLOCK.advance(Duration.ofDays(14).minusMinutes(1));
        TestUser early = user();
        redeem(early, token).andExpect(status().isOk());

        CLOCK.advance(Duration.ofMinutes(2));
        redeem(me, token)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("This invite has expired."));
        assertThat(friendshipRows(me, inviter)).isZero();
    }

    @Test
    void unknownOrMalformedTokenIs404() throws Exception {
        TestUser me = user();
        String unknown = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        for (String token : new String[] {unknown, "short", "x".repeat(200)}) {
            redeem(me, token)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("This invite link is not valid."));
        }
    }

    @Test
    void blockedEitherWayIs404AndChangesNothing() throws Exception {
        TestUser inviter = user();
        TestUser blockedByInviter = user();
        TestUser blockerOfInviter = user();
        block(inviter, blockedByInviter);
        block(blockerOfInviter, inviter);
        String token = createInvite(inviter);

        redeem(blockedByInviter, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("This invite link is not valid."));
        redeem(blockerOfInviter, token).andExpect(status().isNotFound());
        assertThat(friendshipRows(inviter, blockedByInviter)).isZero();
        assertThat(friendshipRows(inviter, blockerOfInviter)).isZero();
    }

    @Test
    void deletingTheInviterKillsTheirInvites() throws Exception {
        TestUser inviter = user();
        TestUser me = user();
        String token = createInvite(inviter);
        mockMvc.perform(delete("/api/v1/me").with(inviter.auth())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from app.invites where inviter_id = ?", Integer.class, inviter.id()))
                .isZero();
        redeem(me, token).andExpect(status().isNotFound());
    }

    private String createInvite(TestUser inviter) throws Exception {
        return JsonPath.read(json(mockMvc.perform(post("/api/v1/invites").with(inviter.auth()))
                .andExpect(status().isOk())), "$.token");
    }

    private org.springframework.test.web.servlet.ResultActions redeem(TestUser user, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/invites/{token}/redeem", token).with(user.auth()));
    }
}
