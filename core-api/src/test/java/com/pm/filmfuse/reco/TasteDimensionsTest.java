package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.model.TasteDimensions;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the fixed interpretable-vector order. These arrays MUST equal python-ml-service's
 * {@code GENRE_IDS} and {@code MOOD_TAGS} in {@code app/services/recommender.py} element for
 * element - a mismatch silently scrambles every stored vector. If this test has to change, change
 * the Python constants (and V9's mood_tag check) in the same commit.
 */
class TasteDimensionsTest {

    /** python-ml-service app/services/recommender.py GENRE_IDS. */
    private static final List<Integer> ML_GENRE_IDS = List.of(
            28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 10752, 37);

    /** python-ml-service app/services/recommender.py MOOD_TAGS. */
    private static final List<String> ML_MOOD_TAGS = List.of(
            "mind-bending", "cozy", "laugh-out-loud", "edge-of-seat", "tearjerker", "visually-stunning",
            "dark-gritty", "feel-good");

    @Test
    void genreOrderMatchesTheMlService() {
        assertThat(TasteDimensions.GENRE_IDS).containsExactlyElementsOf(ML_GENRE_IDS);
    }

    @Test
    void moodOrderMatchesTheMlService() {
        assertThat(TasteDimensions.MOOD_TAGS).containsExactlyElementsOf(ML_MOOD_TAGS);
        assertThat(TasteDimensions.MOOD_LABELS.keySet()).containsExactlyElementsOf(ML_MOOD_TAGS);
    }

    @Test
    void indexesFollowGenresThenMoods() {
        assertThat(TasteDimensions.INTERPRETABLE_DIM).isEqualTo(27);
        assertThat(TasteDimensions.genreIndex(28)).isZero();
        assertThat(TasteDimensions.genreIndex(37)).isEqualTo(18);
        assertThat(TasteDimensions.genreIndex(1)).isEqualTo(-1);
        assertThat(TasteDimensions.moodIndex("mind-bending")).isEqualTo(19);
        assertThat(TasteDimensions.moodIndex("feel-good")).isEqualTo(26);
        assertThat(TasteDimensions.moodIndex("nope")).isEqualTo(-1);
        assertThat(TasteDimensions.genreKey(878)).isEqualTo("genre:878");
        assertThat(TasteDimensions.moodKey("cozy")).isEqualTo("mood:cozy");
        assertThat(TasteDimensions.isMood(null)).isFalse();
        assertThat(TasteDimensions.NEUTRAL_SCORE).isEqualTo(5.0);
    }
}
