package com.pm.filmfuse.reco.repository;

import com.pm.filmfuse.reco.ml.MlFavorite;
import com.pm.filmfuse.reco.ml.MlGenrePref;
import com.pm.filmfuse.reco.ml.MlMoodPref;
import com.pm.filmfuse.reco.ml.MlRating;
import com.pm.filmfuse.reco.model.TasteDimensions;
import com.pm.filmfuse.reco.model.TasteSignals;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads every taste-signal table of one user for the vector build (the same tables the ML service
 * can read itself) and the "already seen" sets used to exclude movies from For you / calibration.
 *
 * <p>Order sent to the ML service: prefs in the fixed vector order, favourites by rank, ratings and
 * id lists by movie id. A rating's {@code createdAt} is when its current score was given
 * ({@code ratings.updated_at}): re-rating overwrites the row.
 */
@Repository
public class TasteSignalRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TasteSignalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TasteSignals load(UUID userId) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", userId);
        List<MlGenrePref> genres = jdbc.query("select genre_id, score from app.user_genre_prefs where user_id = :id", id,
                        (rs, n) -> new MlGenrePref(rs.getInt("genre_id"), rs.getInt("score"))).stream()
                .sorted(Comparator.comparingInt(p -> order(TasteDimensions.genreIndex(p.genreId()))))
                .toList();
        List<MlMoodPref> moods = jdbc.query("select mood_tag, score from app.user_mood_prefs where user_id = :id", id,
                        (rs, n) -> new MlMoodPref(rs.getString("mood_tag"), rs.getInt("score"))).stream()
                .sorted(Comparator.comparingInt(p -> order(TasteDimensions.moodIndex(p.moodTag()))))
                .toList();
        List<MlFavorite> favorites = jdbc.query(
                "select movie_id, rank from app.user_favorite_movies where user_id = :id order by rank", id,
                (rs, n) -> new MlFavorite(rs.getInt("movie_id"), rs.getInt("rank")));
        List<MlRating> ratings = jdbc.query(
                "select movie_id, score, source, updated_at from app.ratings where user_id = :id order by movie_id", id,
                (rs, n) -> new MlRating(rs.getInt("movie_id"), rs.getInt("score"), rs.getString("source"),
                        rs.getTimestamp("updated_at").toInstant().toString()));
        return new TasteSignals(genres, moods, favorites, ratings, unseenIds(userId), dismissedIds(userId));
    }

    public List<Integer> ratedIds(UUID userId) {
        return ids("select movie_id from app.ratings where user_id = :id order by movie_id", userId);
    }

    public List<Integer> unseenIds(UUID userId) {
        return ids("select movie_id from app.rating_unseen where user_id = :id order by movie_id", userId);
    }

    public List<Integer> dismissedIds(UUID userId) {
        return ids("select movie_id from app.rec_dismissals where user_id = :id order by movie_id", userId);
    }

    public List<Integer> favoriteIds(UUID userId) {
        return ids("select movie_id from app.user_favorite_movies where user_id = :id order by movie_id", userId);
    }

    /**
     * Never recommended in For you: rated, marked unseen, dismissed or a favourite (they already
     * love it). Group sessions do not use this - a favourite there is a fair rewatch.
     */
    public Set<Integer> forYouExclusions(UUID userId) {
        Set<Integer> excluded = new HashSet<>(ratedIds(userId));
        excluded.addAll(unseenIds(userId));
        excluded.addAll(dismissedIds(userId));
        excluded.addAll(favoriteIds(userId));
        return excluded;
    }

    /** Never offered for calibration: rated, marked unseen or a favourite. */
    public Set<Integer> calibrationSeen(UUID userId) {
        Set<Integer> seen = new HashSet<>(ratedIds(userId));
        seen.addAll(unseenIds(userId));
        seen.addAll(favoriteIds(userId));
        return seen;
    }

    private List<Integer> ids(String sql, UUID userId) {
        return jdbc.queryForList(sql, new MapSqlParameterSource("id", userId), Integer.class);
    }

    private static int order(int index) {
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
