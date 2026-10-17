package com.pm.filmfuse.reco.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed dimension order of the interpretable interest vector: one dim per TMDB movie genre
 * ({@link #GENRE_IDS}), then one per mood tag ({@link #MOOD_TAGS}). The single source of truth in
 * core-api - the moods endpoint, preference validation, the fallback vector and the taste profile
 * all read it.
 *
 * <p><strong>Must equal python-ml-service's {@code GENRE_IDS} / {@code MOOD_TAGS}</strong> in
 * {@code app/services/recommender.py}, element for element: changing the order changes the meaning
 * of every stored vector. {@code TasteDimensionsTest} documents the expected arrays; change both
 * sides (and V9's mood_tag check) together.
 */
public final class TasteDimensions {

    public static final List<Integer> GENRE_IDS = List.of(
            28, 12, 16, 35, 80, 99, 18, 10751, 14, 36,
            27, 10402, 9648, 10749, 878, 10770, 53, 10752, 37);

    public static final List<String> MOOD_TAGS = List.of(
            "mind-bending",
            "cozy",
            "laugh-out-loud",
            "edge-of-seat",
            "tearjerker",
            "visually-stunning",
            "dark-gritty",
            "feel-good");

    /** Display labels of {@link #MOOD_TAGS}, same order. */
    public static final Map<String, String> MOOD_LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("mind-bending", "Mind-bending");
        labels.put("cozy", "Cozy");
        labels.put("laugh-out-loud", "Laugh-out-loud");
        labels.put("edge-of-seat", "Edge of your seat");
        labels.put("tearjerker", "Tearjerker");
        labels.put("visually-stunning", "Visually stunning");
        labels.put("dark-gritty", "Dark & gritty");
        labels.put("feel-good", "Feel-good");
        MOOD_LABELS = java.util.Collections.unmodifiableMap(labels);
    }

    /** Length of the interpretable part (27). */
    public static final int INTERPRETABLE_DIM = GENRE_IDS.size() + MOOD_TAGS.size();

    /** "Unset" / no opinion on the 1-10 scale. */
    public static final double NEUTRAL_SCORE = 5.0;

    private TasteDimensions() {
    }

    public static boolean isGenre(int genreId) {
        return GENRE_IDS.contains(genreId);
    }

    public static boolean isMood(String tag) {
        return tag != null && MOOD_TAGS.contains(tag);
    }

    /** Index of the genre's dim in the interpretable vector. */
    public static int genreIndex(int genreId) {
        return GENRE_IDS.indexOf(genreId);
    }

    /** Index of the mood's dim in the interpretable vector (after every genre). */
    public static int moodIndex(String tag) {
        int index = MOOD_TAGS.indexOf(tag);
        return index < 0 ? -1 : GENRE_IDS.size() + index;
    }

    /** {@code "genre:28"}. */
    public static String genreKey(int genreId) {
        return "genre:" + genreId;
    }

    /** {@code "mood:cozy"}. */
    public static String moodKey(String tag) {
        return "mood:" + tag;
    }
}
