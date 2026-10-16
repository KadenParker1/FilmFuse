package com.pm.filmfuse.movie.content;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.FixtureMovieCatalogClient;
import com.pm.filmfuse.reco.dto.ContentDimension;
import com.pm.filmfuse.reco.dto.ContentLevel;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.pm.filmfuse.reco.dto.ContentDimension.DRUGS;
import static com.pm.filmfuse.reco.dto.ContentDimension.FRIGHTENING;
import static com.pm.filmfuse.reco.dto.ContentDimension.LANGUAGE;
import static com.pm.filmfuse.reco.dto.ContentDimension.SEXUAL;
import static com.pm.filmfuse.reco.dto.ContentDimension.VIOLENCE;
import static com.pm.filmfuse.reco.dto.ContentLevel.MILD;
import static com.pm.filmfuse.reco.dto.ContentLevel.MODERATE;
import static com.pm.filmfuse.reco.dto.ContentLevel.NONE;
import static com.pm.filmfuse.reco.dto.ContentLevel.STRONG;
import static org.assertj.core.api.Assertions.assertThat;

class KeywordContentLevelProviderTest {

    private final KeywordContentLevelProvider provider = new KeywordContentLevelProvider();

    private static CatalogMovie movie(String certification, String... keywords) {
        return new CatalogMovie(1, "T", 2000, 100, null, null, null, List.of(18), 1.0, 7.0, certification,
                List.of(keywords));
    }

    private Map<ContentDimension, ContentLevel> levels(CatalogMovie movie) {
        Map<ContentDimension, ContentLevelEstimate> estimates = provider.estimate(movie);
        Map<ContentDimension, ContentLevel> levels = new java.util.EnumMap<>(ContentDimension.class);
        estimates.forEach((d, e) -> levels.put(d, e.level()));
        return levels;
    }

    @Test
    void sourceIsKeywords() {
        assertThat(provider.source()).isEqualTo("keywords");
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource({
            "gore, VIOLENCE, STRONG",
            "graphic violence, VIOLENCE, STRONG",
            "torture, VIOLENCE, STRONG",
            "murder, VIOLENCE, MODERATE",
            "shootout, VIOLENCE, MODERATE",
            "war, VIOLENCE, MODERATE",
            "martial arts, VIOLENCE, MILD",
            "slapstick, VIOLENCE, MILD",
            "sex scene, SEXUAL, STRONG",
            "nudity, SEXUAL, MODERATE",
            "brief nudity, SEXUAL, MILD",
            "profanity, LANGUAGE, STRONG",
            "crude humor, LANGUAGE, MODERATE",
            "mild language, LANGUAGE, MILD",
            "heroin, DRUGS, STRONG",
            "drug use, DRUGS, MODERATE",
            "marijuana, DRUGS, MODERATE",
            "smoking, DRUGS, MILD",
            "demonic possession, FRIGHTENING, STRONG",
            "jump scare, FRIGHTENING, STRONG",
            "ghost, FRIGHTENING, MODERATE",
            "zombie, FRIGHTENING, MODERATE",
            "dinosaur, FRIGHTENING, MILD",
            "suspense, FRIGHTENING, MILD"
    })
    void singleKeywords(String keyword, ContentDimension dimension, ContentLevel level) {
        assertThat(levels(movie(null, keyword))).containsExactly(Map.entry(dimension, level));
    }

    @Test
    void keywordsAreMatchedCaseInsensitivelyAfterTrimming() {
        assertThat(levels(movie(null, "  GORE "))).containsEntry(VIOLENCE, STRONG);
    }

    @Test
    void theStrongestKeywordWinsPerDimension() {
        Map<ContentDimension, ContentLevel> result = levels(movie(null, "martial arts", "murder", "gore", "brief nudity"));
        assertThat(result).containsEntry(VIOLENCE, STRONG).containsEntry(SEXUAL, MILD).hasSize(2);
    }

    @Test
    void oneKeywordCanCoverTwoDimensions() {
        assertThat(levels(movie(null, "serial killer")))
                .containsEntry(VIOLENCE, MODERATE).containsEntry(FRIGHTENING, MODERATE).hasSize(2);
        assertThat(levels(movie(null, "peril")))
                .containsEntry(VIOLENCE, MILD).containsEntry(FRIGHTENING, MILD).hasSize(2);
    }

    @Test
    void unmappedKeywordsSayNothing() {
        assertThat(provider.estimate(movie(null, "friendship", "time travel", "based on novel or book"))).isEmpty();
        assertThat(provider.estimate(movie(null))).isEmpty();
    }

    @Test
    void confidenceComesFromTheTable() {
        assertThat(provider.estimate(movie(null, "graphic violence")).get(VIOLENCE).confidence()).isEqualTo(0.9f);
        assertThat(provider.estimate(movie(null, "murder")).get(VIOLENCE).confidence()).isEqualTo(0.7f);
        // On a tie in level, the more confident keyword is kept.
        assertThat(provider.estimate(movie(null, "fight", "martial arts")).get(VIOLENCE).confidence()).isEqualTo(0.6f);
    }

    @Test
    void aGCertificationCapsDimensionsWithoutKeywords() {
        Map<ContentDimension, ContentLevelEstimate> estimates = provider.estimate(movie("G"));
        assertThat(levels(movie("G"))).containsExactlyInAnyOrderEntriesOf(Map.of(
                VIOLENCE, MILD, SEXUAL, NONE, LANGUAGE, NONE, DRUGS, NONE, FRIGHTENING, MILD));
        assertThat(estimates.values()).allMatch(e -> e.confidence() == KeywordContentLevelProvider.CEILING_CONFIDENCE);
    }

    @Test
    void aPgCertificationCapsDimensionsWithoutKeywords() {
        assertThat(levels(movie("PG"))).containsExactlyInAnyOrderEntriesOf(Map.of(
                VIOLENCE, MODERATE, SEXUAL, MILD, LANGUAGE, MILD, DRUGS, NONE, FRIGHTENING, MODERATE));
    }

    @Test
    void keywordsOverrideTheCeilingEvenUpwards() {
        Map<ContentDimension, ContentLevel> result = levels(movie("G", "gore", "smoking"));
        assertThat(result).containsEntry(VIOLENCE, STRONG).containsEntry(DRUGS, MILD).containsEntry(SEXUAL, NONE);
    }

    @Test
    void pg13AndAboveImplyNothingOnTheirOwn() {
        for (String certification : List.of("PG-13", "R", "NC-17")) {
            assertThat(provider.estimate(movie(certification))).as(certification).isEmpty();
            assertThat(levels(movie(certification, "nudity"))).as(certification).containsExactly(Map.entry(SEXUAL, MODERATE));
        }
        assertThat(provider.estimate(movie(null))).isEmpty();
    }

    @Test
    void everyMappedKeywordIsLowercaseAndTrimmed() {
        assertThat(KeywordContentLevelProvider.mappedKeywords())
                .isNotEmpty()
                .allMatch(k -> k.equals(k.trim().toLowerCase(Locale.ROOT)));
    }

    @Test
    void fixtureCatalogFilmsGetSensibleLevels() {
        FixtureMovieCatalogClient catalog = FixtureMovieCatalogClient.fromClasspath();
        // Saving Private Ryan: graphic violence; Toy Story: G, nothing strong.
        assertThat(levels(catalog.details(857))).containsEntry(VIOLENCE, STRONG);
        assertThat(levels(catalog.details(862))).containsEntry(VIOLENCE, MILD).containsEntry(SEXUAL, NONE);
        // Pulp Fiction: heroin, profanity.
        assertThat(levels(catalog.details(680))).containsEntry(DRUGS, STRONG).containsEntry(LANGUAGE, STRONG);
        // Hereditary: demonic possession, decapitation.
        assertThat(levels(catalog.details(493922))).containsEntry(FRIGHTENING, STRONG).containsEntry(VIOLENCE, STRONG);
    }
}
