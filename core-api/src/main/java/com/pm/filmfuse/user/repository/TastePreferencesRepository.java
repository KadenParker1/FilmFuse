package com.pm.filmfuse.user.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.user_genre_prefs}, {@code app.user_mood_prefs}, {@code app.user_favorite_movies}. Replace-all writes. */
@Repository
public class TastePreferencesRepository {

    /** A stored favourite: rank and movie id. */
    public record Favorite(int rank, int movieId) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public TastePreferencesRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serialises concurrent replace-all writes of one user (the profile row lock, held until commit).
     * {@code for no key update}, not {@code for update}: it must not block the {@code for key share}
     * lock that foreign-key checks on other tables take (e.g. a background vector build inserting a
     * history row while holding the vector row would otherwise deadlock with this transaction).
     */
    public void lockUser(UUID userId) {
        jdbc.queryForList("select id from app.profiles where id = :id for no key update",
                new MapSqlParameterSource("id", userId), UUID.class);
    }

    public Map<Integer, Integer> genrePrefs(UUID userId) {
        Map<Integer, Integer> prefs = new LinkedHashMap<>();
        jdbc.query("select genre_id, score from app.user_genre_prefs where user_id = :id",
                new MapSqlParameterSource("id", userId), rs -> {
                    prefs.put(rs.getInt("genre_id"), rs.getInt("score"));
                });
        return prefs;
    }

    public Map<String, Integer> moodPrefs(UUID userId) {
        Map<String, Integer> prefs = new LinkedHashMap<>();
        jdbc.query("select mood_tag, score from app.user_mood_prefs where user_id = :id",
                new MapSqlParameterSource("id", userId), rs -> {
                    prefs.put(rs.getString("mood_tag"), rs.getInt("score"));
                });
        return prefs;
    }

    /** By rank. */
    public List<Favorite> favorites(UUID userId) {
        return jdbc.query("select rank, movie_id from app.user_favorite_movies where user_id = :id order by rank",
                new MapSqlParameterSource("id", userId), (rs, n) -> new Favorite(rs.getInt("rank"), rs.getInt("movie_id")));
    }

    public void replaceGenrePrefs(UUID userId, Map<Integer, Integer> prefs, Instant now) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", userId);
        jdbc.update("delete from app.user_genre_prefs where user_id = :id", id);
        prefs.forEach((genreId, score) -> jdbc.update("""
                insert into app.user_genre_prefs (user_id, genre_id, score, updated_at) values (:id, :genre, :score, :now)
                """, new MapSqlParameterSource("id", userId).addValue("genre", genreId).addValue("score", score)
                .addValue("now", Timestamp.from(now))));
    }

    public void replaceMoodPrefs(UUID userId, Map<String, Integer> prefs, Instant now) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", userId);
        jdbc.update("delete from app.user_mood_prefs where user_id = :id", id);
        prefs.forEach((tag, score) -> jdbc.update("""
                insert into app.user_mood_prefs (user_id, mood_tag, score, updated_at) values (:id, :tag, :score, :now)
                """, new MapSqlParameterSource("id", userId).addValue("tag", tag).addValue("score", score)
                .addValue("now", Timestamp.from(now))));
    }

    public void replaceFavorites(UUID userId, List<Favorite> favorites, Instant now) {
        jdbc.update("delete from app.user_favorite_movies where user_id = :id", new MapSqlParameterSource("id", userId));
        favorites.forEach(f -> jdbc.update("""
                insert into app.user_favorite_movies (user_id, movie_id, rank, created_at) values (:id, :movie, :rank, :now)
                """, new MapSqlParameterSource("id", userId).addValue("movie", f.movieId()).addValue("rank", f.rank())
                .addValue("now", Timestamp.from(now))));
    }
}
