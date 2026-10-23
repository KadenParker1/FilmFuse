package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties.Quality;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QualityFilterTest {

    private static CatalogMovie movie(Integer runtime, String poster, String overview, Integer votes) {
        return new CatalogMovie(1, "M", 2020, runtime, poster, null, overview, List.of(), 1.0, 6.0, null, List.of(),
                votes);
    }

    private final QualityFilter defaults = new QualityFilter(new Quality(null, null, null, null, null, null, null));

    @Test
    void aCompleteMoviePasses() {
        assertThat(defaults.failures(movie(90, "/p.jpg", "A plot.", 5))).isEmpty();
    }

    @Test
    void everyMissingPieceIsNamed() {
        assertThat(defaults.failures(movie(null, null, null, null)))
                .containsExactlyInAnyOrder("poster", "overview", "votes", "runtime");
        assertThat(defaults.failures(movie(0, " ", " ", 4))).containsExactlyInAnyOrder("poster", "overview", "votes",
                "runtime");
    }

    @Test
    void listResultsAreJudgedWithoutRuntime() {
        assertThat(defaults.listFailures(movie(null, "/p.jpg", "A plot.", 5))).isEmpty();
        assertThat(defaults.listFailures(movie(null, "/p.jpg", "A plot.", 4))).containsExactly("votes");
    }

    @Test
    void eachRequirementCanBeSwitchedOff() {
        QualityFilter none = new QualityFilter(new Quality(true, null, false, false, false, 0, null));
        assertThat(none.failures(movie(null, null, null, null))).isEmpty();

        QualityFilter votesOnly = new QualityFilter(new Quality(true, null, false, false, false, 100, null));
        assertThat(votesOnly.failures(movie(null, null, null, 99))).containsExactly("votes");
        assertThat(votesOnly.failures(movie(null, null, null, 100))).isEmpty();
    }

    @Test
    void theBarAppliesToTheConfiguredSourcesOnly() {
        Quality quality = new Quality(null, null, null, null, null, null, null);
        assertThat(quality.appliesTo(Source.EXPORT)).isTrue();
        assertThat(quality.appliesTo(Source.DISCOVER)).isTrue();
        assertThat(quality.appliesTo(Source.POPULAR)).isFalse();
        assertThat(quality.appliesTo(Source.IDS)).isFalse();
        assertThat(new Quality(false, null, null, null, null, null, null).appliesTo(Source.EXPORT)).isFalse();
        assertThat(new Quality(true, List.of(), null, null, null, null, null).appliesTo(Source.EXPORT)).isFalse();
    }
}
