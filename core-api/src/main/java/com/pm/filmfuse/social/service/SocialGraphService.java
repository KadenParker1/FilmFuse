package com.pm.filmfuse.social.service;

import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.dto.RequestDirection;
import com.pm.filmfuse.social.mapper.SocialMapper;
import com.pm.filmfuse.social.model.BlockedUser;
import com.pm.filmfuse.social.model.FriendEdge;
import com.pm.filmfuse.social.model.Friendship;
import com.pm.filmfuse.social.model.FriendsCursor;
import com.pm.filmfuse.social.model.PendingRequest;
import com.pm.filmfuse.social.model.RequestOutcome;
import com.pm.filmfuse.social.model.Suggestion;
import com.pm.filmfuse.social.model.UserPair;
import com.pm.filmfuse.social.repository.BlockRepository;
import com.pm.filmfuse.social.repository.FriendshipRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>The</strong> entry point for the social graph: friendships, pending requests, directional
 * closeness and blocks. No other class touches those tables, so the graph could move to another
 * store (e.g. Neo4j) behind this API. Other modules (groups, feed, recommendations) use the
 * read methods - {@link #areFriends}, {@link #friendIds}, {@link #isBlockedEitherWay},
 * {@link #closeness} - and never query the tables themselves.
 *
 * <p>This class enforces graph invariants (a block clears the pair; closeness rows exist exactly
 * for accepted friendships). HTTP-level rules - which outcome is a 409, which is silent - live in
 * the calling services.
 *
 * <p><strong>Concurrency.</strong> Every write takes a transaction-scoped advisory lock on the pair
 * before reading it, so two requests about the same two people (a request each way, a request
 * racing a block) run one after the other and each sees what the other committed. Two people
 * sending each other requests at the same moment therefore end up friends, never with an error.
 * The {@code friendships} primary key backs this up.
 */
@Service
public class SocialGraphService {

    private final FriendshipRepository friendships;
    private final BlockRepository blocks;

    public SocialGraphService(FriendshipRepository friendships, BlockRepository blocks) {
        this.friendships = friendships;
        this.blocks = blocks;
    }

    // ---------------------------------------------------------------- reads for other modules

    /** True when {@code a} and {@code b} are (accepted) friends. A user is not their own friend. */
    @Transactional(readOnly = true)
    public boolean areFriends(UUID a, UUID b) {
        return !a.equals(b) && friendships.find(UserPair.of(a, b)).map(Friendship::isAccepted).orElse(false);
    }

    /** The ids of everyone {@code user} is friends with, in no particular order. */
    @Transactional(readOnly = true)
    public List<UUID> friendIds(UUID user) {
        return friendships.friendIds(user);
    }

    /** True when either user has blocked the other. */
    @Transactional(readOnly = true)
    public boolean isBlockedEitherWay(UUID a, UUID b) {
        return !a.equals(b) && blocks.existsEitherWay(a, b);
    }

    /** True when {@code blocker} has blocked {@code blocked} (one direction only). */
    @Transactional(readOnly = true)
    public boolean hasBlocked(UUID blocker, UUID blocked) {
        return !blocker.equals(blocked) && blocks.exists(blocker, blocked);
    }

    /**
     * How close {@code from} is to {@code to}, 0-1 (directional): the implicit score, at least 0.8
     * while {@code from} has marked {@code to} a close friend. 0 when they are not friends.
     */
    @Transactional(readOnly = true)
    public double closeness(UUID from, UUID to) {
        if (from.equals(to)) {
            return 0;
        }
        return friendships.closeness(from, to).map(SocialMapper::toDouble).orElse(0d);
    }

    /**
     * {@link #closeness} for every ordered pair of {@code members} in one query:
     * {@code matrix[i][j]} = closeness of members[i] to members[j]; 0 on the diagonal and between
     * people who are not friends.
     */
    @Transactional(readOnly = true)
    public double[][] closenessMatrix(List<UUID> members) {
        int n = members.size();
        double[][] matrix = new double[n][n];
        for (FriendshipRepository.ClosenessRow row : friendships.closenessAmong(new java.util.LinkedHashSet<>(members))) {
            for (int i = 0; i < n; i++) {
                if (!members.get(i).equals(row.from())) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    if (members.get(j).equals(row.to())) {
                        matrix[i][j] = SocialMapper.toDouble(row.closeness());
                    }
                }
            }
        }
        return matrix;
    }

    // ---------------------------------------------------------------- SQL fragments for other modules

    /*
     * A list query that must filter by the graph (the ratings feed) cannot call the methods above
     * per row and still paginate by keyset in one query. These fragments let such a query embed the
     * graph test instead, while the graph-table SQL still lives only in this class. Arguments are
     * SQL expressions (column references or named parameters) - never user input.
     */

    /** SQL: true when {@code a} and {@code b} are accepted friends (false when they are the same user). */
    public static String sqlAreFriends(String a, String b) {
        return """
                exists (select 1 from app.friendships fr
                        where fr.status = 'ACCEPTED'
                          and fr.user_low = least(%1$s, %2$s) and fr.user_high = greatest(%1$s, %2$s))"""
                .formatted(a, b);
    }

    /** SQL: true when either of {@code a} and {@code b} has blocked the other. */
    public static String sqlBlockedEitherWay(String a, String b) {
        return """
                exists (select 1 from app.blocks bl
                        where (bl.blocker_id = %1$s and bl.blocked_id = %2$s)
                           or (bl.blocker_id = %2$s and bl.blocked_id = %1$s))"""
                .formatted(a, b);
    }

    /** SQL: a subquery of the ids of {@code user}'s accepted friends (one column, {@code id}). */
    public static String sqlFriendIds(String user) {
        return """
                select case when fi.user_low = %1$s then fi.user_high else fi.user_low end as id
                from app.friendships fi
                where fi.status = 'ACCEPTED' and (fi.user_low = %1$s or fi.user_high = %1$s)"""
                .formatted(user);
    }

    // ---------------------------------------------------------------- reads for the social module

    /** Everyone who has blocked {@code user}: hidden from {@code user} in every read. */
    @Transactional(readOnly = true)
    public Set<UUID> blockersOf(UUID user) {
        return blocks.blockersOf(user);
    }

    /** {@code other}'s standing relative to {@code viewer}. */
    @Transactional(readOnly = true)
    public FriendshipState stateOf(UUID viewer, UUID other) {
        return statesFor(viewer, List.of(other)).get(other);
    }

    /**
     * Batch {@link #stateOf}: one entry per id in {@code others}, in their order. BLOCKED wins over
     * anything else (a block clears the pair, so there is nothing else). Whether {@code other}
     * blocked {@code viewer} is deliberately not represented.
     */
    @Transactional(readOnly = true)
    public Map<UUID, FriendshipState> statesFor(UUID viewer, Collection<UUID> others) {
        List<UUID> candidates = others.stream().filter(id -> !id.equals(viewer)).distinct().toList();
        Set<UUID> blocked = blocks.blockedAmong(viewer, candidates);
        Map<UUID, Friendship> rows = friendships.findBetween(viewer, candidates);
        Map<UUID, FriendshipState> states = new LinkedHashMap<>();
        for (UUID other : others) {
            states.put(other, state(viewer, other, blocked.contains(other), rows.get(other)));
        }
        return states;
    }

    @Transactional(readOnly = true)
    public int friendCount(UUID user) {
        return friendships.friendCount(user);
    }

    @Transactional(readOnly = true)
    public int mutualFriendCount(UUID a, UUID b) {
        return a.equals(b) ? 0 : friendships.mutualFriendCount(a, b);
    }

    /** A page of {@code user}'s friends: close friends first, then by closeness, then username. */
    @Transactional(readOnly = true)
    public List<FriendEdge> friends(UUID user, FriendsCursor after, int limit) {
        return friendships.friends(user, after, limit);
    }

    /** {@code friend} as one of {@code user}'s friends; empty when they are not friends. */
    @Transactional(readOnly = true)
    public Optional<FriendEdge> friend(UUID user, UUID friend) {
        return user.equals(friend) ? Optional.empty() : friendships.friend(user, friend);
    }

    /** Pending requests to ({@code INCOMING}) or from ({@code OUTGOING}) {@code user}, newest first. */
    @Transactional(readOnly = true)
    public List<PendingRequest> pendingRequests(UUID user, RequestDirection direction) {
        return friendships.pending(user, direction == RequestDirection.OUTGOING);
    }

    /** Friends of friends, best first; see {@link FriendshipRepository#suggestions}. */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(UUID user, int limit, int sampleSize) {
        return friendships.suggestions(user, limit, sampleSize);
    }

    /** Everyone {@code blocker} has blocked, most recent first. */
    @Transactional(readOnly = true)
    public List<BlockedUser> blockedBy(UUID blocker) {
        return blocks.blockedBy(blocker);
    }

    // ---------------------------------------------------------------- writes

    /**
     * {@code requester} asks {@code target} to be friends. A pending request the other way is
     * accepted instead; repeating a pending request changes nothing. Blocks (either way) change
     * nothing and are reported so the caller can decide what to reveal.
     */
    @Transactional
    public RequestOutcome sendRequest(UUID requester, UUID target) {
        UserPair pair = UserPair.of(requester, target);
        friendships.lockPair(pair);
        if (blocks.exists(requester, target)) {
            return RequestOutcome.CALLER_BLOCKED_TARGET;
        }
        if (blocks.exists(target, requester)) {
            return RequestOutcome.TARGET_BLOCKED_CALLER;
        }
        Optional<Friendship> existing = friendships.find(pair);
        if (existing.isEmpty()) {
            if (friendships.insertPending(pair, requester)) {
                return RequestOutcome.SENT;
            }
            // Unreachable while every writer takes the pair lock; re-read rather than trust that.
            existing = friendships.find(pair);
        }
        Friendship friendship = existing.orElseThrow();
        if (friendship.isAccepted()) {
            return RequestOutcome.ALREADY_FRIENDS;
        }
        if (friendship.isPendingFrom(requester)) {
            return RequestOutcome.ALREADY_SENT;
        }
        friendships.accept(pair);
        friendships.ensureCloseness(pair);
        return RequestOutcome.ACCEPTED_REVERSE;
    }

    /** Accepts {@code requester}'s pending request to {@code accepter}; false when there is none. */
    @Transactional
    public boolean acceptRequest(UUID accepter, UUID requester) {
        if (accepter.equals(requester)) {
            return false;
        }
        UserPair pair = UserPair.of(accepter, requester);
        friendships.lockPair(pair);
        boolean pendingFromRequester = friendships.find(pair).map(f -> f.isPendingFrom(requester)).orElse(false);
        if (!pendingFromRequester || !friendships.accept(pair)) {
            return false;
        }
        friendships.ensureCloseness(pair);
        return true;
    }

    /**
     * Removes a pending request from {@code from} to {@code to} (a cancel by {@code from}, or a
     * decline by {@code to}). False when there was none; never touches an accepted friendship.
     */
    @Transactional
    public boolean removeRequest(UUID from, UUID to) {
        if (from.equals(to)) {
            return false;
        }
        UserPair pair = UserPair.of(from, to);
        friendships.lockPair(pair);
        return friendships.deletePendingFrom(pair, from);
    }

    /**
     * Makes {@code a} and {@code b} friends straight away (an invite link: sharing it was the
     * inviter's consent), resolving any pending request between them. Already friends is fine.
     * False, and nothing changes, when either has blocked the other.
     */
    @Transactional
    public boolean befriend(UUID a, UUID b, UUID requestedBy) {
        UserPair pair = UserPair.of(a, b);
        friendships.lockPair(pair);
        if (blocks.existsEitherWay(a, b)) {
            return false;
        }
        friendships.upsertAccepted(pair, requestedBy);
        friendships.ensureCloseness(pair);
        return true;
    }

    /** Ends a friendship (both closeness rows go with it). Idempotent; pending requests are untouched. */
    @Transactional
    public void unfriend(UUID a, UUID b) {
        if (a.equals(b)) {
            return;
        }
        UserPair pair = UserPair.of(a, b);
        friendships.lockPair(pair);
        if (friendships.deleteAccepted(pair)) {
            friendships.deleteCloseness(pair);
        }
    }

    /**
     * Sets {@code user}'s explicit close-friend flag on {@code friend} and returns the updated edge;
     * empty (nothing written) when they are not friends.
     */
    @Transactional
    public Optional<FriendEdge> setCloseFriend(UUID user, UUID friend, boolean closeFriend) {
        if (user.equals(friend)) {
            return Optional.empty();
        }
        UserPair pair = UserPair.of(user, friend);
        friendships.lockPair(pair);
        if (!friendships.find(pair).map(Friendship::isAccepted).orElse(false)) {
            return Optional.empty();
        }
        friendships.setExplicitClose(user, friend, closeFriend);
        return friendships.friend(user, friend);
    }

    /**
     * {@code blocker} blocks {@code blocked}: any friendship, pending request (either way) and
     * closeness between them is removed. Idempotent.
     */
    @Transactional
    public void block(UUID blocker, UUID blocked) {
        UserPair pair = UserPair.of(blocker, blocked);
        friendships.lockPair(pair);
        friendships.delete(pair);
        friendships.deleteCloseness(pair);
        blocks.block(blocker, blocked);
    }

    /** Idempotent. Nothing that the block removed comes back. */
    @Transactional
    public void unblock(UUID blocker, UUID blocked) {
        if (blocker.equals(blocked)) {
            return;
        }
        friendships.lockPair(UserPair.of(blocker, blocked));
        blocks.unblock(blocker, blocked);
    }

    // ---------------------------------------------------------------- helpers

    private static FriendshipState state(UUID viewer, UUID other, boolean viewerBlockedOther, Friendship row) {
        if (viewer.equals(other)) {
            return FriendshipState.NONE;
        }
        if (viewerBlockedOther) {
            return FriendshipState.BLOCKED;
        }
        if (row == null) {
            return FriendshipState.NONE;
        }
        if (row.isAccepted()) {
            return FriendshipState.FRIENDS;
        }
        return row.isPendingFrom(viewer) ? FriendshipState.REQUEST_SENT : FriendshipState.REQUEST_RECEIVED;
    }
}
