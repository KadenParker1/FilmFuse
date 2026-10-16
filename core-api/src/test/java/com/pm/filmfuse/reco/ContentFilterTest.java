package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.dto.ContentDimension;
import com.pm.filmfuse.reco.dto.ContentLevel;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.model.MovieContent;
import com.pm.filmfuse.reco.service.ContentFilterService;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static com.pm.filmfuse.reco.dto.Certification.G;
import static com.pm.filmfuse.reco.dto.Certification.NC_17;
import static com.pm.filmfuse.reco.dto.Certification.PG;
import static com.pm.filmfuse.reco.dto.Certification.PG_13;
import static com.pm.filmfuse.reco.dto.Certification.R;
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

/** The comfort filter's logic: combining filters (most restrictive wins) and eligibility. */
class ContentFilterTest {

    private static ContentFilter filter(Certification max, Map<ContentDimension, ContentLevel> limits, boolean unrated) {
        return new ContentFilter(max, limits, unrated);
    }

    private static MovieContent movie(Certification certification, Map<ContentDimension, ContentLevel> levels) {
        return MovieContent.of(1, certification, levels);
    }

    // ============================================================================ normalisation

    @Nested
    class Normalisation {

        @Test
        void strongAndNullLimitsMeanNoLimitAndAreDropped() {
            Map<ContentDimension, ContentLevel> limits = new HashMap<>();
            limits.put(VIOLENCE, STRONG);
            limits.put(SEXUAL, null);
            limits.put(LANGUAGE, MILD);
            ContentFilter f = filter(null, limits, false);
            assertThat(f.contentLimits()).containsExactly(Map.entry(LANGUAGE, MILD));
            assertThat(f.limit(VIOLENCE)).isEqualTo(STRONG);
            assertThat(f.limit(SEXUAL)).isEqualTo(STRONG);
            assertThat(f).isEqualTo(filter(null, Map.of(LANGUAGE, MILD), false));
        }

        @Test
        void nullLimitsAreEmpty() {
            assertThat(filter(null, null, true).contentLimits()).isEmpty();
            assertThat(filter(null, null, true).hasLimits()).isFalse();
            assertThat(filter(G, null, true).hasLimits()).isTrue();
            assertThat(filter(null, Map.of(DRUGS, NONE), true).hasLimits()).isTrue();
        }

        @Test
        void limitsAreInDimensionOrder() {
            ContentFilter f = filter(null, Map.of(FRIGHTENING, MILD, VIOLENCE, NONE, DRUGS, MODERATE), true);
            assertThat(f.contentLimits().keySet()).containsExactly(VIOLENCE, DRUGS, FRIGHTENING);
        }
    }

    // ============================================================================ most restrictive

    @Nested
    class MostRestrictive {

        static Stream<Arguments> certificationPairs() {
            Stream.Builder<Arguments> pairs = Stream.builder();
            List<Certification> values = new java.util.ArrayList<>(List.of(Certification.values()));
            values.add(null);
            for (Certification a : values) {
                for (Certification b : values) {
                    Certification expected = a == null ? b : b == null ? a : (a.ordinal() <= b.ordinal() ? a : b);
                    pairs.add(Arguments.of(a, b, expected));
                }
            }
            return pairs.build();
        }

        @ParameterizedTest(name = "{0} + {1} -> {2}")
        @MethodSource("certificationPairs")
        void lowestCertificationWinsAndNullMeansNoLimit(Certification a, Certification b, Certification expected) {
            ContentFilter combined = ContentFilter.mostRestrictive(List.of(filter(a, Map.of(), true), filter(b, Map.of(), true)));
            assertThat(combined.maxCertification()).isEqualTo(expected);
            assertThat(Certification.stricter(a, b)).isEqualTo(expected);
        }

        static Stream<Arguments> levelPairs() {
            Stream.Builder<Arguments> pairs = Stream.builder();
            for (ContentDimension dimension : ContentDimension.values()) {
                for (ContentLevel a : ContentLevel.values()) {
                    for (ContentLevel b : ContentLevel.values()) {
                        pairs.add(Arguments.of(dimension, a, b, a.ordinal() <= b.ordinal() ? a : b));
                    }
                }
            }
            return pairs.build();
        }

        @ParameterizedTest(name = "{0}: {1} + {2} -> {3}")
        @MethodSource("levelPairs")
        void lowestLevelWinsPerDimension(ContentDimension dimension, ContentLevel a, ContentLevel b, ContentLevel expected) {
            ContentFilter combined = ContentFilter.mostRestrictive(List.of(
                    filter(null, Map.of(dimension, a), true), filter(null, Map.of(dimension, b), true)));
            assertThat(combined.limit(dimension)).isEqualTo(expected);
            for (ContentDimension other : ContentDimension.values()) {
                if (other != dimension) {
                    assertThat(combined.limit(other)).isEqualTo(STRONG);
                }
            }
        }

        @Test
        void aMissingDimensionDoesNotLoosenAnotherMembersLimit() {
            ContentFilter combined = ContentFilter.mostRestrictive(List.of(
                    filter(null, Map.of(VIOLENCE, MILD), true),
                    filter(null, Map.of(SEXUAL, NONE), true),
                    filter(R, Map.of(), true)));
            assertThat(combined.contentLimits()).containsExactly(Map.entry(VIOLENCE, MILD), Map.entry(SEXUAL, NONE));
            assertThat(combined.maxCertification()).isEqualTo(R);
        }

        @Test
        void allowUnratedOnlyIfEveryoneAllowsIt() {
            assertThat(ContentFilter.mostRestrictive(List.of(filter(null, Map.of(), true), filter(null, Map.of(), true)))
                    .allowUnrated()).isTrue();
            assertThat(ContentFilter.mostRestrictive(List.of(filter(null, Map.of(), true), filter(null, Map.of(), false)))
                    .allowUnrated()).isFalse();
            assertThat(ContentFilter.mostRestrictive(List.of(filter(null, Map.of(), false), filter(null, Map.of(), false)))
                    .allowUnrated()).isFalse();
        }

        @Test
        void nothingIsUnlimited() {
            assertThat(ContentFilter.mostRestrictive(List.of())).isEqualTo(ContentFilter.UNLIMITED);
        }

        @Test
        void aSingleFilterIsItself() {
            ContentFilter only = filter(PG, Map.of(DRUGS, NONE), false);
            assertThat(ContentFilter.mostRestrictive(List.of(only))).isEqualTo(only);
        }

        @Test
        void manyMembersCombineFieldByField() {
            ContentFilter combined = ContentFilter.mostRestrictive(List.of(
                    filter(NC_17, Map.of(VIOLENCE, STRONG, LANGUAGE, MODERATE), true),
                    filter(R, Map.of(VIOLENCE, MODERATE, FRIGHTENING, MILD), true),
                    filter(PG_13, Map.of(LANGUAGE, MILD), true),
                    filter(null, Map.of(FRIGHTENING, NONE, DRUGS, MODERATE), true)));
            assertThat(combined).isEqualTo(filter(PG_13,
                    Map.of(VIOLENCE, MODERATE, LANGUAGE, MILD, FRIGHTENING, NONE, DRUGS, MODERATE), true));
        }
    }

    // ============================================================================ effective (members + cap)

    @Nested
    class Effective {

        @Test
        void theGroupCapTightensButNeverLoosens() {
            ContentFilter member = filter(PG_13, Map.of(VIOLENCE, MILD), false);
            assertThat(ContentFilterService.effective(List.of(member), ContentFilter.cap(PG, Map.of(VIOLENCE, MODERATE))))
                    .isEqualTo(filter(PG, Map.of(VIOLENCE, MILD), false));
            assertThat(ContentFilterService.effective(List.of(member), ContentFilter.cap(R, Map.of())))
                    .isEqualTo(member);
            assertThat(ContentFilterService.effective(List.of(member), ContentFilter.cap(null, Map.of(SEXUAL, NONE))))
                    .isEqualTo(filter(PG_13, Map.of(VIOLENCE, MILD, SEXUAL, NONE), false));
        }

        @Test
        void aCapCannotTurnAllowUnratedOnOrOff() {
            ContentFilter allowing = filter(null, Map.of(), true);
            ContentFilter refusing = filter(null, Map.of(), false);
            // Even a cap built with allowUnrated=false is only a cap.
            ContentFilter strictCap = filter(G, Map.of(), false);
            assertThat(ContentFilterService.effective(List.of(allowing), strictCap).allowUnrated()).isTrue();
            assertThat(ContentFilterService.effective(List.of(refusing), ContentFilter.cap(G, Map.of())).allowUnrated()).isFalse();
        }

        @Test
        void noCapIsJustTheMembers() {
            ContentFilter a = filter(R, Map.of(), true);
            ContentFilter b = filter(PG, Map.of(DRUGS, NONE), true);
            assertThat(ContentFilterService.effective(List.of(a, b), null)).isEqualTo(filter(PG, Map.of(DRUGS, NONE), true));
        }

        @Test
        void noMembersIsStillConservativeAboutUnratedContent() {
            assertThat(ContentFilterService.effective(List.of(), null)).isEqualTo(ContentFilter.USER_DEFAULT);
        }
    }

    // ============================================================================ eligibility

    @Nested
    class Eligibility {

        static Stream<Arguments> certificationBoundaries() {
            Stream.Builder<Arguments> cases = Stream.builder();
            for (Certification max : Certification.values()) {
                for (Certification movie : Certification.values()) {
                    cases.add(Arguments.of(max, movie, movie.ordinal() <= max.ordinal()));
                }
            }
            return cases.build();
        }

        @ParameterizedTest(name = "max {0}, movie {1} -> {2}")
        @MethodSource("certificationBoundaries")
        void aKnownCertificationMustBeAtMostTheCap(Certification max, Certification movie, boolean eligible) {
            assertThat(filter(max, Map.of(), false).isEligible(movie(movie, Map.of()))).isEqualTo(eligible);
            assertThat(filter(max, Map.of(), true).isEligible(movie(movie, Map.of()))).isEqualTo(eligible);
        }

        @ParameterizedTest
        @EnumSource(Certification.class)
        void anUnknownCertificationPassesACapOnlyWhenUnratedIsAllowed(Certification max) {
            assertThat(filter(max, Map.of(), false).isEligible(movie(null, Map.of()))).isFalse();
            assertThat(filter(max, Map.of(), true).isEligible(movie(null, Map.of()))).isTrue();
        }

        @ParameterizedTest
        @EnumSource(Certification.class)
        void withoutACapEveryCertificationPasses(Certification movie) {
            assertThat(filter(null, Map.of(), false).isEligible(movie(movie, Map.of()))).isTrue();
        }

        @Test
        void withoutACapAnUnknownCertificationPassesEvenWhenUnratedIsNotAllowed() {
            assertThat(filter(null, Map.of(), false).isEligible(movie(null, Map.of()))).isTrue();
        }

        static Stream<Arguments> levelBoundaries() {
            Stream.Builder<Arguments> cases = Stream.builder();
            for (ContentDimension dimension : ContentDimension.values()) {
                for (ContentLevel limit : ContentLevel.values()) {
                    for (ContentLevel level : ContentLevel.values()) {
                        cases.add(Arguments.of(dimension, limit, level, level.ordinal() <= limit.ordinal()));
                    }
                }
            }
            return cases.build();
        }

        @ParameterizedTest(name = "{0} limit {1}, movie {2} -> {3}")
        @MethodSource("levelBoundaries")
        void aKnownLevelMustBeAtMostTheLimit(ContentDimension dimension, ContentLevel limit, ContentLevel level,
                                             boolean eligible) {
            MovieContent m = movie(PG, Map.of(dimension, level));
            assertThat(filter(null, Map.of(dimension, limit), false).isEligible(m)).isEqualTo(eligible);
            assertThat(filter(null, Map.of(dimension, limit), true).isEligible(m)).isEqualTo(eligible);
        }

        @ParameterizedTest
        @EnumSource(ContentDimension.class)
        void anUnknownLevelPassesALimitBelowStrongOnlyWhenUnratedIsAllowed(ContentDimension dimension) {
            for (ContentLevel limit : List.of(NONE, MILD, MODERATE)) {
                MovieContent unknown = movie(PG, Map.of());
                assertThat(filter(null, Map.of(dimension, limit), false).isEligible(unknown)).as("%s", limit).isFalse();
                assertThat(filter(null, Map.of(dimension, limit), true).isEligible(unknown)).as("%s", limit).isTrue();
            }
        }

        @ParameterizedTest
        @EnumSource(ContentDimension.class)
        void aStrongLimitAcceptsUnknownLevels(ContentDimension dimension) {
            assertThat(filter(null, Map.of(dimension, STRONG), false).isEligible(movie(PG, Map.of()))).isTrue();
            assertThat(filter(null, Map.of(dimension, STRONG), false).isEligible(movie(PG, Map.of(dimension, STRONG))))
                    .isTrue();
        }

        @Test
        void levelsOfOtherDimensionsDoNotMatter() {
            MovieContent m = movie(PG, Map.of(VIOLENCE, STRONG, SEXUAL, STRONG, LANGUAGE, STRONG, DRUGS, STRONG));
            assertThat(filter(null, Map.of(FRIGHTENING, MILD), true).isEligible(m)).isTrue();
            assertThat(filter(null, Map.of(FRIGHTENING, MILD), false).isEligible(m)).isFalse();
        }

        @Test
        void everyLimitMustPass() {
            ContentFilter f = filter(PG_13, Map.of(VIOLENCE, MODERATE, LANGUAGE, MILD), false);
            assertThat(f.isEligible(movie(PG_13, Map.of(VIOLENCE, MODERATE, LANGUAGE, MILD)))).isTrue();
            assertThat(f.isEligible(movie(PG_13, Map.of(VIOLENCE, MODERATE, LANGUAGE, MODERATE)))).isFalse();
            assertThat(f.isEligible(movie(PG_13, Map.of(VIOLENCE, STRONG, LANGUAGE, NONE)))).isFalse();
            assertThat(f.isEligible(movie(R, Map.of(VIOLENCE, NONE, LANGUAGE, NONE)))).isFalse();
            assertThat(f.isEligible(movie(PG_13, Map.of(VIOLENCE, NONE)))).isFalse();
        }

        @Test
        void unlimitedAcceptsEverything() {
            EnumMap<ContentDimension, ContentLevel> worst = new EnumMap<>(ContentDimension.class);
            for (ContentDimension d : ContentDimension.values()) {
                worst.put(d, STRONG);
            }
            assertThat(ContentFilter.UNLIMITED.isEligible(movie(NC_17, worst))).isTrue();
            assertThat(ContentFilter.USER_DEFAULT.isEligible(movie(null, Map.of()))).isTrue();
        }
    }

    // ============================================================================ label

    @Nested
    class Label {

        @Test
        void noLimits() {
            assertThat(ContentFilter.UNLIMITED.label()).isEqualTo("No limits");
            assertThat(ContentFilter.USER_DEFAULT.label()).isEqualTo("No limits");
        }

        @Test
        void certificationOnly() {
            assertThat(filter(PG_13, Map.of(), false).label()).isEqualTo("Up to PG-13");
            assertThat(filter(NC_17, Map.of(), false).label()).isEqualTo("Up to NC-17");
            assertThat(filter(G, Map.of(), false).label()).isEqualTo("Up to G");
        }

        @Test
        void certificationAndLimits() {
            assertThat(filter(PG_13, Map.of(SEXUAL, NONE, VIOLENCE, MILD), false).label())
                    .isEqualTo("Up to PG-13 · mild violence · no sexual content");
        }

        @Test
        void limitsOnlyStartWithACapital() {
            assertThat(filter(null, Map.of(LANGUAGE, NONE), false).label()).isEqualTo("No bad language");
            assertThat(filter(null, Map.of(DRUGS, MODERATE, FRIGHTENING, MILD), false).label())
                    .isEqualTo("Moderate drug content · mild frightening scenes");
            assertThat(filter(null, Map.of(LANGUAGE, MILD), false).label()).isEqualTo("Mild language");
        }
    }

    // ============================================================================ storage

    @Nested
    class Storage {

        @Test
        void jsonRoundTripsInTheMlVocabulary() {
            ContentFilter f = filter(PG_13, Map.of(VIOLENCE, MILD, DRUGS, NONE), true);
            assertThat(f.toJson()).contains("\"maxCertification\":\"PG-13\"").contains("\"violence\":\"mild\"")
                    .contains("\"drugs\":\"none\"").contains("\"allowUnrated\":true");
            assertThat(ContentFilter.fromJson(f.toJson())).isEqualTo(f);
            assertThat(ContentFilter.fromJson(ContentFilter.USER_DEFAULT.toJson())).isEqualTo(ContentFilter.USER_DEFAULT);
        }

        @Test
        void unknownStoredValuesAreIgnored() {
            assertThat(ContentFilter.limitsFromJson("{\"violence\": \"mild\", \"gore\": \"none\", \"drugs\": \"lots\", \"sexual\": 3}"))
                    .containsExactly(Map.entry(VIOLENCE, MILD));
            assertThat(ContentFilter.fromJson("{}")).isEqualTo(ContentFilter.USER_DEFAULT);
            assertThat(ContentFilter.fromJson(null)).isEqualTo(ContentFilter.USER_DEFAULT);
        }

        @Test
        void wireCodes() {
            assertThat(Certification.PG_13.label()).isEqualTo("PG-13");
            assertThat(Certification.NC_17.label()).isEqualTo("NC-17");
            assertThat(Certification.fromLabel(" pg-13 ")).isEqualTo(PG_13);
            assertThat(Certification.fromLabel("NR")).isNull();
            assertThat(Certification.fromLabel(null)).isNull();
            assertThat(ContentDimension.FRIGHTENING.code()).isEqualTo("frightening");
            assertThat(ContentDimension.fromCode("Sexual")).isEqualTo(SEXUAL);
            assertThat(ContentLevel.MODERATE.code()).isEqualTo("moderate");
            assertThat(ContentLevel.fromCode("nope")).isNull();
            assertThat(filter(null, Map.of(FRIGHTENING, NONE), true).contentLimitCodes())
                    .containsExactly(Map.entry("frightening", "none"));
        }
    }
}
