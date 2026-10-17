package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.dto.VectorChangeReason;
import com.pm.filmfuse.reco.ml.MlProperties;
import com.pm.filmfuse.reco.ml.MlServiceClient;
import com.pm.filmfuse.reco.ml.MlServiceException;
import com.pm.filmfuse.reco.ml.MlUserVector;
import com.pm.filmfuse.reco.ml.MlUserVectorRequest;
import com.pm.filmfuse.reco.ml.MlUserVectorResponse;
import com.pm.filmfuse.reco.model.TasteDimensions;
import com.pm.filmfuse.reco.repository.UserVectorRepository;
import com.pm.filmfuse.reco.service.FallbackVectorBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dirty marking → VectorRefreshScheduler → ML user-vectors (or the fallback) → new version + history. */
class VectorPipelineIntegrationTest extends AbstractTasteIntegrationTest {

    @Autowired
    private UserVectorRepository vectors;

    private static MlUserVectorResponse mlVector(double first) {
        List<Double> values = new ArrayList<>(Collections.nCopies(TasteDimensions.INTERPRETABLE_DIM, 5.0));
        values.set(0, first);
        return new MlUserVectorResponse(new MlUserVector(values, List.of(0.25, -0.5)), 29, "owner-1");
    }

    // ------------------------------------------------------------------------------- scheduler

    @Test
    void theSchedulerWaitsForTheDebounceThenBuilds() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");

        drainRefresh();
        assertThat(version(user)).isZero();
        assertThat(isDirty(user)).isTrue();

        CLOCK.advance(Duration.ofSeconds(5));
        drainRefresh();
        assertThat(version(user)).isZero();

        CLOCK.advance(Duration.ofSeconds(6));
        drainRefresh();
        assertThat(version(user)).isEqualTo(1);
        assertThat(isDirty(user)).isFalse();
        assertThat(vectorRow(user).get("dirty_since")).isNull();
        assertThat(vectorRow(user).get("dirty_reason")).isNull();
    }

    @Test
    void aNewWriteRestartsTheDebounce() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        CLOCK.advance(Duration.ofSeconds(8));
        setGenres(user, "[{\"genreId\": 28, \"score\": 8}]");
        CLOCK.advance(Duration.ofSeconds(8));
        drainRefresh();
        assertThat(version(user)).isZero();
        CLOCK.advance(Duration.ofSeconds(3));
        drainRefresh();
        assertThat(version(user)).isEqualTo(1);
    }

    @Test
    void aNewerDirtyMarkDuringTheBuildKeepsTheVectorDirty() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userVector(any())).thenAnswer(invocation -> {
            MlUserVectorRequest request = invocation.getArgument(0);
            if (request.userId().equals(user.id())) {
                // The user rates something while the ML service is computing.
                rate(user, movie(uniqueGenre(), "PG", 1), 4);
            }
            return mlVector(9.0);
        });
        CLOCK.advance(PAST_DEBOUNCE);
        drainRefresh();

        assertThat(version(user)).isEqualTo(1);
        assertThat(isDirty(user)).isTrue();
        assertThat(dirtyReason(user)).isEqualTo("RATING");
        // The stored version was built for the earlier signal.
        assertThat(jdbc.queryForObject("select reason from app.user_interest_vector_history where user_id = ? and version = 1",
                String.class, user.id())).isEqualTo("TASTE_EDIT");

        doReturn(mlVector(8.0)).when(mlClient).userVector(any());
        CLOCK.advance(PAST_DEBOUNCE);
        drainRefresh();
        assertThat(version(user)).isEqualTo(2);
        assertThat(isDirty(user)).isFalse();
    }

    @Test
    void oneUsersFailureDoesNotStopTheOthers() throws Exception {
        TestUser broken = user();
        TestUser fine = user();
        setGenres(broken, "[{\"genreId\": 28, \"score\": 9}]");
        setGenres(fine, "[{\"genreId\": 28, \"score\": 9}]");
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userVector(any())).thenAnswer(invocation -> {
            MlUserVectorRequest request = invocation.getArgument(0);
            if (request.userId().equals(broken.id())) {
                throw new IllegalStateException("bug");
            }
            return mlVector(7.0);
        });
        CLOCK.advance(PAST_DEBOUNCE);
        drainRefresh();
        assertThat(version(fine)).isEqualTo(1);
        assertThat(version(broken)).isZero();
        assertThat(isDirty(broken)).isTrue();
    }

    // ------------------------------------------------------------------------------- reasons

    @Test
    void theLatestReasonWins() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        assertThat(dirtyReason(user)).isEqualTo("TASTE_EDIT");
        rate(user, movie(genre, "PG", 1), 8);
        assertThat(dirtyReason(user)).isEqualTo("RATING");
        mockMvc.perform(post("/api/v1/me/recommendations/{id}/dismiss", movie(genre, "PG", 1)).with(user.auth()))
                .andExpect(status().isNoContent());
        assertThat(dirtyReason(user)).isEqualTo("DISMISS");
        setMoods(user, "[{\"moodTag\": \"cozy\", \"score\": 9}]");
        assertThat(dirtyReason(user)).isEqualTo("TASTE_EDIT");

        buildVector(user);
        assertThat(jdbc.queryForObject("select reason from app.user_interest_vector_history where user_id = ? and version = 1",
                String.class, user.id())).isEqualTo("TASTE_EDIT");
    }

    @Test
    void dirtySinceStrictlyIncreasesEvenWithinOneInstant() throws Exception {
        TestUser user = user();
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        jdbc.update("insert into app.user_interest_vectors (user_id) values (?)", user.id());
        vectors.markDirty(user.id(), VectorChangeReason.RATING, now);
        Instant first = vectors.find(user.id()).orElseThrow().dirtySince();
        vectors.markDirty(user.id(), VectorChangeReason.DISMISS, now);
        Instant second = vectors.find(user.id()).orElseThrow().dirtySince();
        assertThat(first).isEqualTo(now);
        assertThat(second).isAfter(first);
        assertThat(vectors.find(user.id()).orElseThrow().dirtyReason()).isEqualTo(VectorChangeReason.DISMISS);
    }

    // ------------------------------------------------------------------------------- fallback / ML

    @Test
    void withoutAnMlServiceTheFallbackCopiesStatedScoresInFixedOrder() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 37, \"score\": 2}, {\"genreId\": 28, \"score\": 9}]");
        setMoods(user, "[{\"moodTag\": \"feel-good\", \"score\": 10}, {\"moodTag\": \"mind-bending\", \"score\": 1}]");
        rate(user, movie(uniqueGenre(), "PG", 1), 10);
        buildVector(user);

        List<Double> expected = new ArrayList<>(Collections.nCopies(TasteDimensions.INTERPRETABLE_DIM, 5.0));
        expected.set(TasteDimensions.GENRE_IDS.indexOf(28), 9.0);
        expected.set(TasteDimensions.GENRE_IDS.indexOf(37), 2.0);
        expected.set(TasteDimensions.GENRE_IDS.size() + TasteDimensions.MOOD_TAGS.indexOf("mind-bending"), 1.0);
        expected.set(TasteDimensions.GENRE_IDS.size() + TasteDimensions.MOOD_TAGS.indexOf("feel-good"), 10.0);
        assertThat(interpretable(user)).isEqualTo(expected);
        Map<String, Object> row = vectorRow(user);
        assertThat(row.get("model_version")).isEqualTo("fallback-0");
        assertThat(row.get("dim")).isEqualTo(27);
        assertThat(row.get("latent")).isNull();
    }

    @Test
    void historyKeepsEveryVersionWithItsReason() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        assertThat(buildVector(user)).isEqualTo(1);
        rate(user, movie(uniqueGenre(), "PG", 1), 3);
        assertThat(buildVector(user)).isEqualTo(2);
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userVector(any())).thenReturn(mlVector(6.5));
        setGenres(user, "[{\"genreId\": 28, \"score\": 2}]");
        assertThat(buildVector(user)).isEqualTo(3);

        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, reason, model_version, dim from app.user_interest_vector_history where user_id = ? order by version",
                user.id());
        assertThat(history).extracting(r -> r.get("version")).containsExactly(1, 2, 3);
        assertThat(history).extracting(r -> r.get("reason")).containsExactly("TASTE_EDIT", "RATING", "TASTE_EDIT");
        assertThat(history).extracting(r -> r.get("model_version")).containsExactly("fallback-0", "fallback-0", "owner-1");
        assertThat(history.get(2).get("dim")).isEqualTo(29);
        assertThat(interpretable(user).getFirst()).isEqualTo(6.5);
        assertThat(vectorRow(user).get("latent")).isNotNull();
        // Not dirty: nothing more to build.
        assertThat(vectorBuilder.rebuildIfDirty(user.id())).isEmpty();
    }

    @Test
    void anMlFailureKeepsAnExistingVectorAndRetries() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        buildVector(user);
        List<Double> before = interpretable(user);

        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userVector(any())).thenThrow(new MlServiceException("timeout"));
        setGenres(user, "[{\"genreId\": 28, \"score\": 1}]");
        assertThat(vectorBuilder.rebuildIfDirty(user.id())).isEmpty();
        assertThat(version(user)).isEqualTo(1);
        assertThat(isDirty(user)).isTrue();
        assertThat(interpretable(user)).isEqualTo(before);

        doReturn(mlVector(1.0)).when(mlClient).userVector(any());
        assertThat(vectorBuilder.rebuildIfDirty(user.id())).contains(2);
        assertThat(isDirty(user)).isFalse();
    }

    @Test
    void theMlRequestCarriesEverySignalInTheContractShape() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int quick = movie(genre, "PG", 1);
        int watched = movie(genre, "PG", 1);
        int swiped = movie(genre, "PG", 1);
        int unseen = movie(genre, "PG", 1);
        int dismissed = movie(genre, "PG", 1);
        int favorite = movie(genre, "PG", 1);
        setGenres(user, "[{\"genreId\": 878, \"score\": 9}, {\"genreId\": 28, \"score\": 4}]");
        setMoods(user, "[{\"moodTag\": \"cozy\", \"score\": 7}]");
        setFavorites(user, favorite);
        rate(user, quick, 6);
        rate(user, watched, "{\"score\": 9, \"source\": \"POST_WATCH\"}").andExpect(status().isOk());
        postJson(user, "/api/v1/me/ratings/batch", "[{\"movieId\": " + swiped + ", \"score\": 2},"
                + " {\"movieId\": " + unseen + ", \"unseen\": true}]").andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/me/recommendations/{id}/dismiss", dismissed).with(user.auth()))
                .andExpect(status().isNoContent());
        buildVector(user); // v1 via the fallback, so the next request has a previousVector
        rate(user, quick, 7);

        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userVector(any())).thenReturn(mlVector(5.0));
        buildVector(user);
        ArgumentCaptor<MlUserVectorRequest> captor = ArgumentCaptor.forClass(MlUserVectorRequest.class);
        verify(mlClient, atLeastOnce()).userVector(captor.capture());
        MlUserVectorRequest request = captor.getAllValues().stream()
                .filter(r -> r.userId().equals(user.id())).reduce((a, b) -> b).orElseThrow();

        // Serialise it exactly as production does and compare with the contract (camelCase, lowercase sources).
        StringBuilder previous = new StringBuilder();
        for (Double v : request.previousVector().interpretable()) {
            previous.append(previous.isEmpty() ? "" : ",").append(v);
        }
        String expected = """
                {
                  "userId": "%s",
                  "previousVector": {"interpretable": [%s], "latent": null},
                  "genrePrefs": [{"genreId": 28, "score": 4}, {"genreId": 878, "score": 9}],
                  "moodPrefs": [{"moodTag": "cozy", "score": 7}],
                  "favorites": [{"movieId": %d, "rank": 1}],
                  "ratings": [%s],
                  "unseen": [%d],
                  "dismissals": [%d]
                }
                """.formatted(user.id(), previous, favorite, ratingsJson(user.id(), Map.of(
                quick, "{\"movieId\": %d, \"score\": 7, \"source\": \"quick_rate\", \"createdAt\": \"%s\"}",
                watched, "{\"movieId\": %d, \"score\": 9, \"source\": \"post_watch\", \"createdAt\": \"%s\"}",
                swiped, "{\"movieId\": %d, \"score\": 2, \"source\": \"onboarding_swipe\", \"createdAt\": \"%s\"}")),
                unseen, dismissed);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MlServiceClient wire = new MlServiceClient(builder, new MlProperties("http://ml.internal", "k"));
        server.expect(requestTo("http://ml.internal/internal/user-vectors"))
                .andExpect(content().json(expected, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"vector": {"interpretable": [1.0, 2.0], "latent": null}, "dim": 2, "modelVersion": "x"}
                        """, MediaType.APPLICATION_JSON));
        wire.userVector(request);
        server.verify();
        assertThat(request.previousVector().interpretable()).hasSize(TasteDimensions.INTERPRETABLE_DIM);
        assertThat(request.ratings()).extracting(r -> Instant.parse(r.createdAt())).doesNotContainNull();
    }

    @Test
    void aSessionPickMarksEveryMembersVectorDirtyWithPick() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        java.util.UUID groupId = activeGroup(owner, member);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 10);
        UUID sessionId = sessionId(createGroupSession(owner, groupId, genre));
        pick(member, sessionId, movie).andExpect(status().isOk());
        assertThat(dirtyReason(owner)).isEqualTo("PICK");
        assertThat(dirtyReason(member)).isEqualTo("PICK");
    }

    @Test
    void theFallbackLabelIsStable() {
        assertThat(FallbackVectorBuilder.MODEL_VERSION).isEqualTo("fallback-0");
    }

    /** The expected ratings array: by movie id, as TasteSignalRepository sends them. */
    private String ratingsJson(UUID userId, Map<Integer, String> templates) {
        return templates.keySet().stream().sorted()
                .map(id -> templates.get(id).formatted(id, updatedAt(userId, id)))
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String updatedAt(UUID userId, int movieId) {
        return jdbc.queryForObject("select updated_at from app.ratings where user_id = ? and movie_id = ?",
                java.sql.Timestamp.class, userId, movieId).toInstant().toString();
    }

    // ------------------------------------------------------------------------------- concurrency / async

    @Test
    void twoConcurrentBuildsStoreOneVersionNotTwo() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        java.util.concurrent.CountDownLatch inFlight = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(mlClient.isConfigured()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            MlUserVectorRequest request = invocation.getArgument(0);
            if (request != null && request.userId().equals(user.id())) {
                inFlight.countDown();
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            return mlVector(7.0);
        }).when(mlClient).userVector(any());

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> vectorBuilder.rebuildIfDirty(user.id()));
            var b = pool.submit(() -> vectorBuilder.rebuildIfDirty(user.id()));
            // Both builds read version 0 and are inside the ML call at the same time.
            assertThat(inFlight.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            List<java.util.Optional<Integer>> results = List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    b.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).filteredOn(java.util.Optional::isPresent).hasSize(1)
                    .first().isEqualTo(java.util.Optional.of(1));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(version(user)).isEqualTo(1);
        assertThat(jdbc.queryForList("select version from app.user_interest_vector_history where user_id = ?",
                Integer.class, user.id())).containsExactly(1);
        assertThat(isDirty(user)).isFalse();
    }

    @Test
    void aStaleBuildIsDiscardedRatherThanStoredOutOfOrder() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        java.time.Instant now = java.time.Instant.now();
        // A build that read version 0 tries to store after version 1 already exists.
        assertThat(vectors.storeNewVersion(user.id(), 0, new MlUserVector(List.of(1.0), null), 1, "a",
                VectorChangeReason.TASTE_EDIT, null, now)).contains(1);
        assertThat(vectors.storeNewVersion(user.id(), 0, new MlUserVector(List.of(2.0), null), 1, "b",
                VectorChangeReason.TASTE_EDIT, null, now)).isEmpty();
        assertThat(version(user)).isEqualTo(1);
        assertThat(vectorRow(user).get("model_version")).isEqualTo("a");
        assertThat(count("user_interest_vector_history", user.id())).isEqualTo(1);
    }

    @Test
    void aRejectedBackgroundBuildIsLeftToTheScheduler() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        com.pm.filmfuse.reco.service.VectorBuildTrigger trigger = new com.pm.filmfuse.reco.service.VectorBuildTrigger(
                task -> {
                    throw new org.springframework.core.task.TaskRejectedException("queue full");
                }, vectorBuilder);

        trigger.requestRebuild(user.id()); // never throws
        assertThat(version(user)).isZero();
        assertThat(isDirty(user)).isTrue();

        CLOCK.advance(PAST_DEBOUNCE);
        drainRefresh();
        assertThat(version(user)).isEqualTo(1);
    }

    @Test
    void theBuildExecutorIsSmallAndBounded() {
        assertThat(vectorBuildExecutor.getCorePoolSize()).isEqualTo(2);
        assertThat(vectorBuildExecutor.getMaxPoolSize()).isEqualTo(2);
        assertThat(vectorBuildExecutor.getQueueCapacity()).isEqualTo(20);
        assertThat(vectorBuildExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
    }

    @Autowired
    private com.pm.filmfuse.user.repository.TastePreferencesRepository preferencesRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** Regression: a preference write's profile lock must not block a build's history insert (FK check). */
    @Test
    void aPreferenceWritesLockDoesNotBlockStoringAVersion() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        preferencesRepository.lockUser(user.id());
                        locked.countDown();
                        try {
                            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var store = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status ->
                            vectors.storeNewVersion(user.id(), 0, new MlUserVector(List.of(5.0), null), 1, "m",
                                    VectorChangeReason.TASTE_EDIT, null, Instant.now())));
            assertThat(store.get(5, java.util.concurrent.TimeUnit.SECONDS)).contains(1);
            release.countDown();
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
