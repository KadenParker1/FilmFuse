package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.config.AppProperties;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.GoneException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.dto.InviteDTO;
import com.pm.filmfuse.social.dto.InviteRedeemResultDTO;
import com.pm.filmfuse.social.mapper.SocialMapper;
import com.pm.filmfuse.social.model.Invite;
import com.pm.filmfuse.social.model.SocialProfile;
import com.pm.filmfuse.social.repository.InviteRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shareable invite links. The token (32 random bytes, base64url) appears only in the link; the
 * database keeps its SHA-256, so a database leak yields no working links. A link works for any
 * number of people until it expires. Redeeming makes the redeemer and the inviter friends at once:
 * sharing the link was the inviter's consent.
 */
@Service
public class InviteService {

    static final Duration LIFETIME = Duration.ofDays(14);
    static final int TOKEN_BYTES = 32;
    static final String INVITE_NOT_FOUND = "This invite link is not valid.";
    static final String INVITE_EXPIRED = "This invite has expired.";
    static final String OWN_INVITE = "You can't redeem your own invite.";

    /** A base64url token of 32 bytes is 43 characters; anything much longer is not ours. */
    private static final int MAX_TOKEN_LENGTH = 128;

    private final InviteRepository invites;
    private final SocialGraphService graph;
    private final SocialUsers users;
    private final String webUrl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public InviteService(InviteRepository invites, SocialGraphService graph, SocialUsers users,
                         AppProperties appProperties, Clock clock) {
        this.invites = invites;
        this.graph = graph;
        this.users = users;
        this.webUrl = appProperties.webUrl();
        this.clock = clock;
    }

    public InviteDTO create(UUID callerId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // Microsecond precision: what Postgres stores, so the expiry the client sees is the one enforced.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Invite invite = new Invite(UUID.randomUUID(), callerId, now, now.plus(LIFETIME));
        invites.insert(invite, hash(token));
        return new InviteDTO(token, webUrl + "/invite/" + token, invite.expiresAt());
    }

    /**
     * 404 for an unknown token, or when either person has blocked the other (indistinguishable on
     * purpose); 400 for one's own invite; 410 once expired. Already friends is a success.
     */
    @Transactional
    public InviteRedeemResultDTO redeem(UUID callerId, String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new NotFoundException(INVITE_NOT_FOUND);
        }
        Invite invite = invites.findByTokenHash(hash(token.trim()))
                .orElseThrow(() -> new NotFoundException(INVITE_NOT_FOUND));
        UUID inviterId = invite.inviterId();
        if (inviterId.equals(callerId)) {
            throw new BadRequestException(OWN_INVITE);
        }
        if (!clock.instant().isBefore(invite.expiresAt())) {
            throw new GoneException(INVITE_EXPIRED);
        }
        SocialProfile inviter;
        try {
            inviter = users.requireActive(inviterId);
        } catch (NotFoundException ex) {
            throw new NotFoundException(INVITE_NOT_FOUND);
        }
        if (!graph.befriend(callerId, inviterId, inviterId)) {
            throw new NotFoundException(INVITE_NOT_FOUND);
        }
        return new InviteRedeemResultDTO(SocialMapper.toSummaryDTO(inviter.user(), FriendshipState.FRIENDS));
    }

    /** SHA-256 of the token's text. */
    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }
}
