package com.pm.filmfuse.movie.content;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.dto.ContentDimension;
import com.pm.filmfuse.reco.dto.ContentLevel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

import static com.pm.filmfuse.reco.dto.ContentDimension.DRUGS;
import static com.pm.filmfuse.reco.dto.ContentDimension.FRIGHTENING;
import static com.pm.filmfuse.reco.dto.ContentDimension.LANGUAGE;
import static com.pm.filmfuse.reco.dto.ContentDimension.SEXUAL;
import static com.pm.filmfuse.reco.dto.ContentDimension.VIOLENCE;
import static com.pm.filmfuse.reco.dto.ContentLevel.MILD;
import static com.pm.filmfuse.reco.dto.ContentLevel.MODERATE;
import static com.pm.filmfuse.reco.dto.ContentLevel.NONE;
import static com.pm.filmfuse.reco.dto.ContentLevel.STRONG;

/**
 * v1 content levels: a heuristic over TMDB keywords, plus a ceiling implied by family
 * certifications. TMDB has no parental-guide data, so this is deliberately simple and readable -
 * tune the table below, not the code.
 *
 * <p>How a dimension's level is decided:
 * <ol>
 *   <li><b>Keywords.</b> Every keyword in {@link #KEYWORDS} that the movie has votes for a level;
 *       the strongest wins (with that keyword's confidence; the highest confidence on a tie).</li>
 *   <li><b>Certification ceiling.</b> With no keyword for a dimension, a G or PG certification
 *       still says something: MPAA rules cap what such a film can contain ({@link #G_CEILING},
 *       {@link #PG_CEILING}). PG-13 and above say nothing about a single dimension.</li>
 *   <li>Otherwise the dimension is left unknown.</li>
 * </ol>
 * A keyword never lowers a level, so a G film tagged "gore" is still STRONG violence.
 */
@Component
public class KeywordContentLevelProvider implements ContentLevelProvider {

    public static final String SOURCE = "keywords";

    /** Confidence of a level implied only by a G / PG certification. */
    static final float CEILING_CONFIDENCE = 0.5f;

    private record Rule(ContentDimension dimension, ContentLevel level, float confidence) {
    }

    // ---------------------------------------------------------------------------- the mapping
    // keyword (TMDB keyword name, lowercase) -> dimension, level, confidence. A keyword may appear
    // under several dimensions ("serial killer" is violence and frightening).
    // Confidence: 0.9 = the keyword names the content outright ("graphic violence");
    //             0.7 = strongly implies it ("serial killer"); 0.5 = suggests it ("ghost").
    private static final Map<String, List<Rule>> KEYWORDS = new HashMap<>();

    static {
        // VIOLENCE
        rule(VIOLENCE, STRONG, 0.9f, "gore", "graphic violence", "extreme violence", "splatter", "torture",
                "decapitation", "dismemberment", "massacre", "brutality", "bloodbath", "violent death",
                "mutilation", "cannibalism");
        rule(VIOLENCE, MODERATE, 0.7f, "violence", "murder", "shootout", "gunfight", "serial killer", "hitman",
                "assassin", "assassination", "war", "battle", "world war ii", "world war i", "combat",
                "fistfight", "shooting", "stabbing", "execution", "gangster", "mafia", "revenge killing",
                "bank robbery", "holocaust");
        rule(VIOLENCE, MILD, 0.6f, "sword fight", "swordplay", "martial arts", "fight", "slapstick",
                "cartoon violence", "action hero", "duel", "lightsaber", "superhero", "peril");
        // SEXUAL
        rule(SEXUAL, STRONG, 0.9f, "sex scene", "explicit sex", "erotic movie", "erotica", "rape",
                "sexual abuse", "pornography", "orgy");
        rule(SEXUAL, MODERATE, 0.8f, "nudity", "female nudity", "male nudity", "sexuality", "prostitution",
                "prostitute", "strip club", "affair", "one-night stand", "seduction");
        rule(SEXUAL, MILD, 0.6f, "brief nudity", "kissing", "innuendo", "sexual humor", "first kiss");
        // LANGUAGE
        rule(LANGUAGE, STRONG, 0.9f, "profanity", "strong language", "swearing", "vulgarity", "f word",
                "foul language");
        rule(LANGUAGE, MODERATE, 0.6f, "crude humor", "rude humor", "insult");
        rule(LANGUAGE, MILD, 0.5f, "mild language", "name-calling");
        // DRUGS
        rule(DRUGS, STRONG, 0.9f, "drug addiction", "heroin", "cocaine", "drug overdose", "overdose",
                "methamphetamine", "drug abuse", "junkie");
        rule(DRUGS, MODERATE, 0.7f, "drug use", "drugs", "drug dealer", "drug trafficking", "marijuana",
                "alcoholism", "drug cartel", "hallucinogen", "lsd");
        rule(DRUGS, MILD, 0.5f, "smoking", "drunkenness", "alcohol", "cigarette");
        // FRIGHTENING
        rule(FRIGHTENING, STRONG, 0.9f, "jump scare", "supernatural horror", "demonic possession", "demon",
                "body horror", "slasher", "found footage", "occult", "exorcism", "psychological horror",
                "haunting");
        rule(FRIGHTENING, MODERATE, 0.7f, "haunted house", "ghost", "zombie", "psychopath", "creature",
                "monster", "serial killer", "psychological thriller", "insanity", "nightmare");
        rule(FRIGHTENING, MILD, 0.5f, "witch", "dragon", "dinosaur", "alien", "spooky", "dark fantasy",
                "dystopia", "suspense", "peril");
    }

    // A dimension with no keyword evidence still gets the most a family certification allows
    // (MPAA: G = nothing a parent would find offensive; PG = no drug content, at most mild sex and
    // language). Violence / frightening stay above NONE because G and PG films have cartoon peril.
    static final Map<ContentDimension, ContentLevel> G_CEILING = ceiling(MILD, NONE, NONE, NONE, MILD);
    static final Map<ContentDimension, ContentLevel> PG_CEILING = ceiling(MODERATE, MILD, MILD, NONE, MODERATE);

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public Map<ContentDimension, ContentLevelEstimate> estimate(CatalogMovie movie) {
        EnumMap<ContentDimension, ContentLevelEstimate> result = new EnumMap<>(ContentDimension.class);
        for (String keyword : movie.keywords()) {
            for (Rule rule : KEYWORDS.getOrDefault(keyword, List.of())) {
                result.merge(rule.dimension(), new ContentLevelEstimate(rule.level(), rule.confidence()),
                        KeywordContentLevelProvider::stronger);
            }
        }
        Map<ContentDimension, ContentLevel> ceiling = switch (Certification.fromLabel(movie.certification())) {
            case G -> G_CEILING;
            case PG -> PG_CEILING;
            case null, default -> Map.of();
        };
        ceiling.forEach((dimension, level) ->
                result.putIfAbsent(dimension, new ContentLevelEstimate(level, CEILING_CONFIDENCE)));
        return Collections.unmodifiableMap(result);
    }

    private static void rule(ContentDimension dimension, ContentLevel level, float confidence, String... keywords) {
        for (String keyword : keywords) {
            KEYWORDS.computeIfAbsent(keyword, k -> new ArrayList<>()).add(new Rule(dimension, level, confidence));
        }
    }

    private static ContentLevelEstimate stronger(ContentLevelEstimate a, ContentLevelEstimate b) {
        if (a.level() != b.level()) {
            return a.level().ordinal() > b.level().ordinal() ? a : b;
        }
        return a.confidence() >= b.confidence() ? a : b;
    }

    private static Map<ContentDimension, ContentLevel> ceiling(ContentLevel violence, ContentLevel sexual,
                                                               ContentLevel language, ContentLevel drugs,
                                                               ContentLevel frightening) {
        EnumMap<ContentDimension, ContentLevel> map = new EnumMap<>(ContentDimension.class);
        map.put(VIOLENCE, violence);
        map.put(SEXUAL, sexual);
        map.put(LANGUAGE, language);
        map.put(DRUGS, drugs);
        map.put(FRIGHTENING, frightening);
        return Collections.unmodifiableMap(map);
    }

    /** Every mapped keyword (for tests and tooling). */
    static Set<String> mappedKeywords() {
        return Collections.unmodifiableSet(KEYWORDS.keySet());
    }
}
