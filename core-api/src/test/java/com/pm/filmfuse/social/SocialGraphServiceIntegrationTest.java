package com.pm.filmfuse.social;

import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.model.RequestOutcome;
import com.pm.filmfuse.social.service.SocialGraphService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The graph API other modules build on, and its behaviour under concurrent writes. */
class SocialGraphServiceIntegrationTest extends AbstractSocialIntegrationTest {

    @Autowired
    private SocialGraphService graph;

    @Test
    void areFriendsAndFriendIds() throws Exception {
        TestUser me = user();
        TestUser a = user();
        TestUser b = user();
        TestUser pending = user();
        befriend(me, a);
        befriend(b, me);
        sendRequest(me, pending).andExpect(status().isOk());

        assertThat(graph.areFriends(me.id(), a.id())).isTrue();
        assertThat(graph.areFriends(a.id(), me.id())).isTrue();
        assertThat(graph.areFriends(me.id(), pending.id())).isFalse();
        assertThat(graph.areFriends(a.id(), b.id())).isFalse();
        assertThat(graph.areFriends(me.id(), me.id())).isFalse();
        assertThat(graph.friendIds(me.id())).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(graph.friendIds(UUID.randomUUID())).isEmpty();
        assertThat(graph.friendCount(me.id())).isEqualTo(2);
        assertThat(graph.mutualFriendCount(a.id(), b.id())).isEqualTo(1);
    }

    @Test
    void isBlockedEitherWayAndHasBlocked() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        assertThat(graph.isBlockedEitherWay(me.id(), bob.id())).isFalse();
        block(me, bob);
        assertThat(graph.isBlockedEitherWay(me.id(), bob.id())).isTrue();
        assertThat(graph.isBlockedEitherWay(bob.id(), me.id())).isTrue();
        assertThat(graph.hasBlocked(me.id(), bob.id())).isTrue();
        assertThat(graph.hasBlocked(bob.id(), me.id())).isFalse();
        assertThat(graph.blockersOf(bob.id())).containsExactly(me.id());
        assertThat(graph.isBlockedEitherWay(me.id(), me.id())).isFalse();
    }

    @Test
    void closenessIsDirectionalAndZeroForNonFriends() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        assertThat(graph.closeness(me.id(), bob.id())).isZero();
        befriend(me, bob);
        assertThat(graph.closeness(me.id(), bob.id())).isEqualTo(0.5);
        graph.setCloseFriend(me.id(), bob.id(), true);
        assertThat(graph.closeness(me.id(), bob.id())).isEqualTo(0.8);
        assertThat(graph.closeness(bob.id(), me.id())).isEqualTo(0.5);
        jdbc.update("update app.friend_closeness set score = 0.25 where user_id = ? and friend_id = ?", bob.id(), me.id());
        assertThat(graph.closeness(bob.id(), me.id())).isCloseTo(0.25, within(1e-9));
        assertThat(graph.closeness(me.id(), me.id())).isZero();
    }

    @Test
    void statesForReportsEachRelationshipInOrder() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        TestUser sent = user();
        TestUser received = user();
        TestUser blocked = user();
        TestUser blocker = user();
        TestUser none = user();
        befriend(me, friend);
        sendRequest(me, sent).andExpect(status().isOk());
        sendRequest(received, me).andExpect(status().isOk());
        block(me, blocked);
        block(blocker, me);

        Map<UUID, FriendshipState> states = graph.statesFor(me.id(),
                List.of(none.id(), friend.id(), sent.id(), received.id(), blocked.id(), blocker.id(), me.id()));
        assertThat(states).containsExactly(
                Map.entry(none.id(), FriendshipState.NONE),
                Map.entry(friend.id(), FriendshipState.FRIENDS),
                Map.entry(sent.id(), FriendshipState.REQUEST_SENT),
                Map.entry(received.id(), FriendshipState.REQUEST_RECEIVED),
                Map.entry(blocked.id(), FriendshipState.BLOCKED),
                // A block against the viewer is never revealed.
                Map.entry(blocker.id(), FriendshipState.NONE),
                Map.entry(me.id(), FriendshipState.NONE));
    }

    @Test
    void simultaneousMutualRequestsEndAsFriends() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                TestUser a = user();
                TestUser b = user();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Callable<RequestOutcome> aAsks = () -> {
                    barrier.await();
                    return graph.sendRequest(a.id(), b.id());
                };
                Callable<RequestOutcome> bAsks = () -> {
                    barrier.await();
                    return graph.sendRequest(b.id(), a.id());
                };
                Future<RequestOutcome> first = pool.submit(aAsks);
                Future<RequestOutcome> second = pool.submit(bAsks);

                assertThat(List.of(first.get(), second.get()))
                        .containsExactlyInAnyOrder(RequestOutcome.SENT, RequestOutcome.ACCEPTED_REVERSE);
                assertThat(graph.areFriends(a.id(), b.id())).isTrue();
                assertThat(closenessRows(a, b)).isEqualTo(2);
                assertThat(friendshipRows(a, b)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousMutualRequestsOverHttpEndAsFriends() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            TestUser a = user();
            TestUser b = user();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Integer> first = pool.submit(() -> {
                barrier.await();
                return sendRequest(a, b).andReturn().getResponse().getStatus();
            });
            Future<Integer> second = pool.submit(() -> {
                barrier.await();
                return sendRequest(b, a).andReturn().getResponse().getStatus();
            });
            assertThat(List.of(first.get(), second.get())).containsOnly(200);
            assertThat(stateOf(a, b)).isEqualTo("FRIENDS");
            mockMvc.perform(get("/api/v1/friends").with(a.auth())).andExpect(status().isOk());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void requestRacingABlockNeverLeavesARequestBehind() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                TestUser a = user();
                TestUser b = user();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> ask = pool.submit(() -> {
                    barrier.await();
                    return graph.sendRequest(a.id(), b.id());
                });
                Future<?> blocks = pool.submit(() -> {
                    barrier.await();
                    graph.block(b.id(), a.id());
                    return null;
                });
                ask.get();
                blocks.get();
                assertThat(friendshipRows(a, b)).isZero();
                assertThat(graph.hasBlocked(b.id(), a.id())).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
