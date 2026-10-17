package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.model.PersonaTemplates;
import com.pm.filmfuse.reco.model.TasteDimensions;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaTemplatesTest {

    @Test
    void moodWordThenGenreModifierThenGenrePersona() {
        assertThat(PersonaTemplates.persona("cozy", 878, 10749)).isEqualTo("Cozy Sci-Fi Romantic");
        assertThat(PersonaTemplates.persona("mind-bending", 53, 80)).isEqualTo("Cerebral Thriller Sleuth");
    }

    @Test
    void everyGenreAndMoodHasWords() {
        for (String mood : TasteDimensions.MOOD_TAGS) {
            for (int genre : TasteDimensions.GENRE_IDS) {
                assertThat(PersonaTemplates.persona(mood, genre, genre)).isNotBlank();
            }
        }
    }

    @Test
    void unknownPartsGiveNoPersona() {
        assertThat(PersonaTemplates.persona("spooky", 28, 12)).isNull();
        assertThat(PersonaTemplates.persona("cozy", 1, 12)).isNull();
    }

    @Test
    void fallbacksWhenFewGenresOrNoMoodAreAboveNeutral() {
        assertThat(PersonaTemplates.persona("cozy", List.of(878, 10749, 28))).isEqualTo("Cozy Sci-Fi Romantic");
        assertThat(PersonaTemplates.persona("cozy", List.of(878))).isEqualTo("Cozy Futurist");
        assertThat(PersonaTemplates.persona("cozy", List.of())).isEqualTo("Cozy Film Buff");
        assertThat(PersonaTemplates.persona(null, List.of(878, 10749))).isEqualTo("Eclectic Sci-Fi Romantic");
        assertThat(PersonaTemplates.persona(null, List.of())).isEqualTo("Eclectic Film Buff");
        assertThat(PersonaTemplates.persona("spooky", List.of(1, 878))).isEqualTo("Eclectic Futurist");
    }
}
