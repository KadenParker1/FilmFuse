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
    void listResultsOmitCertificationAndKeywordsLikeTmdb() {
        assertThat(client.popular(null, 1).movies()).allMatch(movie -> movie.certification() == null
                && movie.keywords().isEmpty());
    }

    @Test
    void detailsCarryRealCertificationsAndKeywords() {
        List<CatalogMovie> all = allPopular().stream().map(m -> client.details(m.id())).toList();
        assertThat(all).allMatch(movie -> !movie.keywords().isEmpty());
        assertThat(all).extracting(CatalogMovie::certification)
                .contains("G", "PG", "PG-13", "R")
                .containsNull()
                .allMatch(c -> c == null || Set.of("G", "PG", "PG-13", "R", "NC-17").contains(c));
        assertThat(client.details(862).certification()).isEqualTo("G");
        assertThat(client.details(155).certification()).isEqualTo("PG-13");
        assertThat(client.details(550).certification()).isEqualTo("R");
        assertThat(client.details(129).certification()).isEqualTo("PG");
        assertThat(client.details(550).keywords()).contains("fistfight");
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

    @Test
    void discoverByYearListsOnlyThatYearsMoviesWithEnoughVotesMostVotedFirst() {
        CatalogMovie a = new CatalogMovie(1, "A", 1999, 90, null, null, "a", List.of(18), 5.0, 7.0, null, List.of(), 10);
        CatalogMovie b = new CatalogMovie(2, "B", 1999, 90, null, null, "b", List.of(18), 9.0, 7.0, null, List.of(), 900);
        CatalogMovie c = new CatalogMovie(3, "C", 1999, 90, null, null, "c", List.of(18), 1.0, 7.0, null, List.of(), 2);
        CatalogMovie d = new CatalogMovie(4, "D", 2000, 90, null, null, "d", List.of(18), 1.0, 7.0, null, List.of(), 500);
        CatalogMovie unknownVotes = new CatalogMovie(5, "E", 1999, 90, null, null, "e", List.of(18), 1.0, 7.0);
        FixtureMovieCatalogClient small = new FixtureMovieCatalogClient(List.of(a, b, c, d, unknownVotes));

        CatalogPage page = small.discoverByYear(1999, 5, 1);

        assertThat(page.movies()).extracting(CatalogMovie::id).containsExactly(2, 1, 5);
        assertThat(page.movies()).allMatch(movie -> movie.runtime() == null);
        assertThat(page.movies().getFirst().voteCount()).isEqualTo(900);
        assertThat(page.hasNext()).isFalse();
        assertThat(small.discoverByYear(1850, 0, 1).movies()).isEmpty();
    }
}
