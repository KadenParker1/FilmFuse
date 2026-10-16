package com.pm.filmfuse.group;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.group.realtime.RealtimeGateway;
import com.pm.filmfuse.reco.ml.MlServiceClient;
import com.pm.filmfuse.social.AbstractSocialIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for group / session / comfort integration tests. Shares one Spring context: the social
 * base's clock and email spy, plus a mocked {@link MlServiceClient} (unconfigured by default, so
 * sessions use the FallbackRanker unless a test stubs it) and a spy on the realtime gateway.
 *
 * <p>The database is shared and never wiped. Every test seeds its own movies under a genre id no
 * other test uses ({@link #uniqueGenre()}) and creates its sessions with that genre as a filter,
 * so its candidate pool contains exactly its own movies.
 */
public abstract class AbstractGroupIntegrationTest extends AbstractSocialIntegrationTest {

    @MockitoBean
    protected MlServiceClient mlClient;

    @MockitoSpyBean
    protected RealtimeGateway realtimeGateway;

    /** A genre id no other test (and no real TMDB genre) uses. */
    protected static int uniqueGenre() {
        return ThreadLocalRandom.current().nextInt(1_000_000, 900_000_000);
    }

    /** A cached movie under {@code genre}; {@code levels} maps lowercase dimension to lowercase level. */
    protected int movie(int genre, String certification, double popularity, Map<String, String> levels) {
        int id = ThreadLocalRandom.current().nextInt(1_000_000_000, Integer.MAX_VALUE);
        jdbc.update("""
                insert into app.movies (id, title, release_year, runtime, genre_ids, popularity, vote_average, cached_at,
                                        certification)
                values (?, ?, 2001, 100, ?, ?, 7.0, ?, ?)
                """, ps -> {
            ps.setInt(1, id);
            ps.setString(2, "Movie " + id);
            ps.setArray(3, ps.getConnection().createArrayOf("int4", new Object[] {genre}));
            ps.setDouble(4, popularity);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.setString(6, certification);
        });
        levels.forEach((dimension, level) -> jdbc.update("""
                insert into app.movie_content_levels (movie_id, dimension, level, source, confidence)
                values (?, ?, ?, 'manual', 1)
                """, id, dimension, level));
        return id;
    }

    protected int movie(int genre, String certification, double popularity) {
        return movie(genre, certification, popularity, Map.of());
    }

    protected void setRuntimeAndYear(int movieId, Integer runtime, Integer year) {
        jdbc.update("update app.movies set runtime = ?, release_year = ? where id = ?", runtime, year, movieId);
    }

    protected void setComfort(TestUser user, String json) throws Exception {
        mockMvc.perform(put("/api/v1/me/settings/comfort").with(user.auth())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------- groups

    protected ResultActions createGroup(TestUser owner, String name, TestUser... invitees) throws Exception {
        String ids = Arrays.stream(invitees).map(u -> "\"" + u.id() + "\"").collect(Collectors.joining(","));
        return mockMvc.perform(post("/api/v1/groups").with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + name + "\", \"memberIds\": [" + ids + "]}"));
    }

    /** A group owned by {@code owner} whose other members are friends of the owner who accepted. */
    protected UUID activeGroup(TestUser owner, TestUser... members) throws Exception {
        for (TestUser member : members) {
            befriend(owner, member);
        }
        UUID id = UUID.fromString(JsonPath.read(json(createGroup(owner, "Movie night", members)
                .andExpect(status().isCreated())), "$.id"));
        for (TestUser member : members) {
            acceptGroupInvite(member, id).andExpect(status().isOk());
        }
        return id;
    }

    protected ResultActions acceptGroupInvite(TestUser user, UUID groupId) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/{id}/invites/accept", groupId).with(user.auth()));
    }

    protected ResultActions getGroup(TestUser user, UUID groupId) throws Exception {
        return mockMvc.perform(get("/api/v1/groups/{id}", groupId).with(user.auth()));
    }

    // ------------------------------------------------------------------------------- sessions

    protected ResultActions createGroupSession(TestUser caller, UUID groupId, int genre) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions").with(caller.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\": \"" + groupId + "\", \"filters\": {\"genreIds\": [" + genre + "]}}"));
    }

    protected ResultActions createAdHocSession(TestUser caller, int genre, TestUser... friends) throws Exception {
        String ids = Arrays.stream(friends).map(u -> "\"" + u.id() + "\"").collect(Collectors.joining(","));
        return mockMvc.perform(post("/api/v1/sessions").with(caller.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberIds\": [" + ids + "], \"filters\": {\"genreIds\": [" + genre + "]}}"));
    }

    protected UUID sessionId(ResultActions created) throws Exception {
        return UUID.fromString(JsonPath.read(json(created.andExpect(status().isCreated())), "$.id"));
    }

    protected ResultActions getSession(TestUser user, UUID sessionId) throws Exception {
        return mockMvc.perform(get("/api/v1/sessions/{id}", sessionId).with(user.auth()));
    }

    protected ResultActions vote(TestUser user, UUID sessionId, int movieId, String vote) throws Exception {
        return mockMvc.perform(put("/api/v1/sessions/{id}/votes/{movieId}", sessionId, movieId).with(user.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"vote\": \"" + vote + "\"}"));
    }

    protected ResultActions pick(TestUser user, UUID sessionId, int movieId) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{id}/pick", sessionId).with(user.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"movieId\": " + movieId + "}"));
    }

    /** The result movie ids of a SessionDTO, in response order. */
    protected static List<Integer> resultIds(String sessionJson) {
        return JsonPath.read(sessionJson, "$.results[*].movie.id");
    }
}
