package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.ml.MlFavorite;
import com.pm.filmfuse.reco.ml.MlGenrePref;
import com.pm.filmfuse.reco.ml.MlMoodPref;
import com.pm.filmfuse.reco.ml.MlRating;
import com.pm.filmfuse.reco.ml.MlUserVectorRequest;
import com.pm.filmfuse.reco.ml.MlUserVectorResponse;
import com.pm.filmfuse.reco.model.TasteDimensions;
import com.pm.filmfuse.reco.service.FallbackVectorBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The trivial, labelled fallback: stated scores in GENRE_IDS then MOOD_TAGS order, neutral 5 elsewhere. */
class FallbackVectorBuilderTest {

    @Test
    void copiesStatedScoresInTheFixedOrderAndIgnoresEverythingElse() {
        MlUserVectorRequest request = new MlUserVectorRequest(UUID.randomUUID(), null,
                List.of(new MlGenrePref(37, 1), new MlGenrePref(28, 10)),
                List.of(new MlMoodPref("feel-good", 2), new MlMoodPref("mind-bending", 9)),
                List.of(new MlFavorite(603, 1)),
                List.of(new MlRating(603, 10, "quick_rate", "2026-10-16T00:00:00Z")),
                List.of(13), List.of(550));

        MlUserVectorResponse response = new FallbackVectorBuilder().build(request);

        List<Double> expected = new ArrayList<>(Collections.nCopies(27, 5.0));
        expected.set(0, 10.0);   // 28 is GENRE_IDS[0]
        expected.set(18, 1.0);   // 37 is GENRE_IDS[18]
        expected.set(19, 9.0);   // mind-bending is MOOD_TAGS[0]
        expected.set(26, 2.0);   // feel-good is MOOD_TAGS[7]
        assertThat(response.vector().interpretable()).containsExactlyElementsOf(expected);
        assertThat(response.vector().latent()).isNull();
        assertThat(response.dim()).isEqualTo(TasteDimensions.INTERPRETABLE_DIM);
        assertThat(response.modelVersion()).isEqualTo("fallback-0");
    }

    @Test
    void noPrefsIsAllNeutral() {
        MlUserVectorResponse response = new FallbackVectorBuilder().build(new MlUserVectorRequest(UUID.randomUUID(),
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        assertThat(response.vector().interpretable()).hasSize(27).containsOnly(5.0);
    }
}
