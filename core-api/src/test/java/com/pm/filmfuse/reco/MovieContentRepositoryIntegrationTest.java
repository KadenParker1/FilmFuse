package com.pm.filmfuse.reco;

import com.pm.filmfuse.group.AbstractGroupIntegrationTest;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.dto.ContentDimension;
import com.pm.filmfuse.reco.dto.ContentLevel;
import com.pm.filmfuse.reco.model.CandidateCriteria;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.model.MovieContent;
import com.pm.filmfuse.reco.repository.MovieContentRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL eligible-pool query must agree exactly with ContentFilter.isEligible (the Java re-check):
 * a matrix of movies (every certification incl. unknown x every violence level incl. unknown, plus
 * a language level) is run through many filters both ways.
 */
class MovieContentRepositoryIntegrationTest extends AbstractGroupIntegrationTest {

    @Autowired
    private MovieContentRepository repository;

    @Test
    void sqlPoolMatchesTheJavaFilterForEveryCombination() {
        int genre = uniqueGenre();
        List<String> certifications = Arrays.asList("G", "PG", "PG-13", "R", "NC-17", null);
        List<String> levels = Arrays.asList("none", "mild", "moderate", "strong", null);
        double popularity = 1000;
        for (String certification : certifications) {
            for (String violence : levels) {
                Map<String, String> movieLevels = new HashMap<>();
                if (violence != null) {
                    movieLevels.put("violence", violence);
                }
                if (popularity % 2 == 0) {
                    movieLevels.put("language", "mild");
                }
                movie(genre, certification, popularity--, movieLevels);
            }
        }
        CandidateCriteria criteria = new CandidateCriteria(null, null, List.of(genre));
        List<Integer> all = repository.eligiblePool(ContentFilter.UNLIMITED, criteria, Set.of(), 500);
        assertThat(all).hasSize(30);
        Map<Integer, MovieContent> content = repository.findContent(all);

        List<ContentFilter> filters = new ArrayList<>();
        List<Certification> caps = new ArrayList<>(Arrays.asList(Certification.values()));
        caps.add(null);
        for (Certification cap : caps) {
            for (boolean allowUnrated : new boolean[] {false, true}) {
                filters.add(new ContentFilter(cap, Map.of(), allowUnrated));
                for (ContentLevel limit : ContentLevel.values()) {
                    filters.add(new ContentFilter(cap, Map.of(ContentDimension.VIOLENCE, limit), allowUnrated));
                    filters.add(new ContentFilter(cap, Map.of(ContentDimension.VIOLENCE, limit,
                            ContentDimension.LANGUAGE, ContentLevel.NONE), allowUnrated));
                }
            }
        }
        for (ContentFilter filter : filters) {
            List<Integer> expected = all.stream().filter(id -> filter.isEligible(content.get(id))).toList();
            assertThat(repository.eligiblePool(filter, criteria, Set.of(), 500)).as(filter.toString())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void poolIsMostPopularFirstLimitedAndExcludesGivenIds() {
        int genre = uniqueGenre();
        int a = movie(genre, "PG", 30);
        int b = movie(genre, "PG", 20);
        int c = movie(genre, "PG", 10);
        CandidateCriteria criteria = new CandidateCriteria(null, null, List.of(genre));

        assertThat(repository.eligiblePool(ContentFilter.UNLIMITED, criteria, Set.of(), 500)).containsExactly(a, b, c);
        assertThat(repository.eligiblePool(ContentFilter.UNLIMITED, criteria, Set.of(), 2)).containsExactly(a, b);
        assertThat(repository.eligiblePool(ContentFilter.UNLIMITED, criteria, Set.of(a, c), 500)).containsExactly(b);
    }

    @Test
    void sessionCriteriaAgreeWithTheJavaCheck() {
        int genre = uniqueGenre();
        int other = uniqueGenre();
        List<Integer> ids = new ArrayList<>();
        for (Integer runtime : Arrays.asList(80, 120, 200, null)) {
            for (Integer year : Arrays.asList(1950, 2000, null)) {
                int id = movie(genre, "PG", ids.size());
                setRuntimeAndYear(id, runtime, year);
                ids.add(id);
            }
        }
        int otherGenre = movie(other, "PG", 1);
        Map<Integer, MovieContent> content = repository.findContent(ids);
        for (CandidateCriteria criteria : List.of(
                new CandidateCriteria(120, null, List.of(genre)),
                new CandidateCriteria(null, 1990, List.of(genre)),
                new CandidateCriteria(100, 1990, List.of(genre, 12345)))) {
            List<Integer> expected = ids.stream().filter(id -> criteria.matches(content.get(id)))
                    .sorted(Comparator.comparing(id -> -ids.indexOf(id))).toList();
            assertThat(repository.eligiblePool(ContentFilter.UNLIMITED, criteria, Set.of(), 500))
                    .as(criteria.toString()).containsExactlyElementsOf(expected).doesNotContain(otherGenre);
        }
    }

    @Test
    void findContentReadsCertificationLevelsAndCriteriaFields() {
        int genre = uniqueGenre();
        int id = movie(genre, "NC-17", 1, Map.of("violence", "strong", "drugs", "none"));
        MovieContent movie = repository.findContent(List.of(id, -1)).get(id);
        assertThat(movie.certification()).isEqualTo(Certification.NC_17);
        assertThat(movie.levels()).containsExactlyInAnyOrderEntriesOf(Map.of(ContentDimension.VIOLENCE, ContentLevel.STRONG,
                ContentDimension.DRUGS, ContentLevel.NONE));
        assertThat(movie.runtime()).isEqualTo(100);
        assertThat(movie.releaseYear()).isEqualTo(2001);
        assertThat(movie.genreIds()).containsExactly(genre);
        assertThat(repository.findContent(List.of())).isEmpty();
        assertThat(repository.findContent(List.of(-1))).isEmpty();
    }
}
