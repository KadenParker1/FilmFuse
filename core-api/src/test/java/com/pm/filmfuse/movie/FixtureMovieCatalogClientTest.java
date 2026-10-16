package com.pm.filmfuse.movie;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.FixtureMovieCatalogClient;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureMovieCatalogClientTest {

    /** The 19 TMDB movie genre ids seeded by V3. */
    static final Set<Integer> TMDB_GENRE_IDS = Set.of(28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648,
            10749, 878, 10770, 53, 10752, 37);

    private final FixtureMovieCatalogClient client = FixtureMovieCatalogClient.fromClasspath();

    @Test
    void fixtureIsWellFormed() {
        List<CatalogMovie> all = allPopular();
        assertThat(all).hasSizeGreaterThanOrEqualTo(40);
        assertThat(all).extracting(CatalogMovie::id).doesNotHaveDuplicates();
        for (CatalogMovie listed : all) {
            CatalogMovie movie = client.details(listed.id());
            assertThat(movie.title()).isNotBlank();
            assertThat(movie.releaseYear()).isBetween(1900, 2030);
            assertThat(movie.runtime()).isPositive();
            assertThat(movie.overview()).isNotBlank();
            assertThat(movie.genreIds()).isNotEmpty().allMatch(TMDB_GENRE_IDS::contains);
            assertThat(movie.popularity()).isPositive();
            assertThat(movie.voteAverage()).isBetween(0d, 10d);
            assertThat(movie.posterPath()).satisfiesAnyOf(
                    path -> assertThat(path).isNull(),
                    path -> assertThat(path).matches("^/\\w+\\.jpg$"));
        }
        // Spans many genres, so genre filters have something to show.
        Set<Integer> covered = all.stream().flatMap(m -> m.genreIds().stream()).collect(Collectors.toSet());
        assertThat(covered).hasSizeGreaterThanOrEqualTo(17);
    }

    @Test
    void searchIsCaseInsensitiveTitleContains() {
        CatalogPage page = client.search("  MATRIX ", 1);
        assertThat(page.movies()).extracting(CatalogMovie::title).containsExactly("The Matrix");
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void searchWithNoMatchIsAnEmptyLastPage() {
        CatalogPage page = client.search("zzzz-no-such-film", 1);
        assertThat(page.movies()).isEmpty();
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void popularIsSortedByPopularityDescending() {
        List<CatalogMovie> all = allPopular();
        assertThat(all).extracting(CatalogMovie::popularity)
                .isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void popularPagesAreTwentyLongAndTheLastHasNoNext() {
        int total = allPopular().size();
        int pages = (total + 19) / 20;

        for (int page = 1; page < pages; page++) {
            CatalogPage result = client.popular(null, page);
            assertThat(result.movies()).hasSize(FixtureMovieCatalogClient.PAGE_SIZE);
            assertThat(result.hasNext()).isTrue();
        }
        CatalogPage last = client.popular(null, pages);
        assertThat(last.movies()).hasSize(total - (pages - 1) * 20);
        assertThat(last.hasNext()).isFalse();

        CatalogPage pastTheEnd = client.popular(null, pages + 1);
        assertThat(pastTheEnd.movies()).isEmpty();
        assertThat(pastTheEnd.hasNext()).isFalse();
    }

    @Test
    void popularFiltersByGenre() {
        CatalogPage horror = client.popular(27, 1);
        assertThat(horror.movies()).isNotEmpty().allMatch(movie -> movie.genreIds().contains(27));
        assertThat(horror.hasNext()).isFalse();

        assertThat(client.popular(10770, 1).movies()).isEmpty();
    }

    @Test
    void listResultsOmitRuntimeLikeTmdb() {
        assertThat(client.popular(null, 1).movies()).allMatch(movie -> movie.runtime() == null);
        assertThat(client.search("the", 1).movies()).allMatch(movie -> movie.runtime() == null);
    }

    @Test
    void detailsIncludeRuntime() {
        CatalogMovie fightClub = client.details(550);
        assertThat(fightClub.title()).isEqualTo("Fight Club");
        assertThat(fightClub.releaseYear()).isEqualTo(1999);
        assertThat(fightClub.runtime()).isEqualTo(139);
    }

    @Test
    void unknownIdIsNotFound() {
        assertThatThrownBy(() -> client.details(999_999_999))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Movie not found.");
    }

    private List<CatalogMovie> allPopular() {
        return IntStream.rangeClosed(1, 10)
                .mapToObj(page -> client.popular(null, page).movies())
                .flatMap(List::stream)
                .toList();
    }
}
