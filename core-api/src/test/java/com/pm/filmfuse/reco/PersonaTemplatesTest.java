package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.model.PersonaTemplates;
import com.pm.filmfuse.reco.model.TasteDimensions;
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
}
