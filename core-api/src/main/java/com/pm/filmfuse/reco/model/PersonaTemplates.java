package com.pm.filmfuse.reco.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <strong>Cosmetic only.</strong> Turns the top mood and the top two genres into a shareable label
 * for the taste-reveal card: {@code <mood word> <genre modifier> <genre persona>}, e.g. "Cozy Sci-Fi
 * Romantic". With only one genre above neutral it is {@code <mood word> <genre persona>} ("Cozy
 * Futurist"); with none, {@code <mood word> Film Buff}; with no mood above neutral the mood word is
 * {@value #NO_MOOD_WORD}. A fixed lookup table - no taste logic; edit the words freely.
 */
public final class PersonaTemplates {

    /** Mood tag → leading adjective. */
    static final Map<String, String> MOOD_WORDS = Map.of(
            "mind-bending", "Cerebral",
            "cozy", "Cozy",
            "laugh-out-loud", "Playful",
            "edge-of-seat", "Thrill-Seeking",
            "tearjerker", "Sentimental",
            "visually-stunning", "Aesthetic",
            "dark-gritty", "Brooding",
            "feel-good", "Sunny");

    /** Genre id → {modifier, persona noun}. */
    static final Map<Integer, String[]> GENRE_WORDS = Map.ofEntries(
            Map.entry(28, new String[] {"Action", "Adrenaline Junkie"}),
            Map.entry(12, new String[] {"Adventure", "Explorer"}),
            Map.entry(16, new String[] {"Animated", "Dreamer"}),
            Map.entry(35, new String[] {"Comedy", "Jokester"}),
            Map.entry(80, new String[] {"Crime", "Sleuth"}),
            Map.entry(99, new String[] {"Documentary", "Truth-Seeker"}),
            Map.entry(18, new String[] {"Drama", "Dramatist"}),
            Map.entry(10751, new String[] {"Family", "Homebody"}),
            Map.entry(14, new String[] {"Fantasy", "Myth-Maker"}),
            Map.entry(36, new String[] {"Period", "Historian"}),
            Map.entry(27, new String[] {"Horror", "Fright Fan"}),
            Map.entry(10402, new String[] {"Musical", "Music Lover"}),
            Map.entry(9648, new String[] {"Mystery", "Puzzler"}),
            Map.entry(10749, new String[] {"Romance", "Romantic"}),
            Map.entry(878, new String[] {"Sci-Fi", "Futurist"}),
            Map.entry(10770, new String[] {"TV-Movie", "Couch Critic"}),
            Map.entry(53, new String[] {"Thriller", "Thrill-Seeker"}),
            Map.entry(10752, new String[] {"War", "Strategist"}),
            Map.entry(37, new String[] {"Western", "Gunslinger"}));

    static final String NO_MOOD_WORD = "Eclectic";
    static final String NO_GENRE_NOUN = "Film Buff";

    private PersonaTemplates() {
    }

    /**
     * Never null. {@code topMood} is the best mood above neutral (null if none), {@code topGenres} the
     * genres above neutral, best first (may be empty; only the first two count). Unknown tags/ids are
     * ignored.
     */
    public static String persona(String topMood, List<Integer> topGenres) {
        String mood = topMood == null ? null : MOOD_WORDS.get(topMood);
        List<String[]> genres = new ArrayList<>();
        for (Integer id : topGenres) {
            String[] words = GENRE_WORDS.get(id);
            if (words != null && genres.size() < 2) {
                genres.add(words);
            }
        }
        String lead = mood == null ? NO_MOOD_WORD : mood;
        return switch (genres.size()) {
            case 0 -> lead + " " + NO_GENRE_NOUN;
            case 1 -> lead + " " + genres.get(0)[1];
            default -> lead + " " + genres.get(0)[0] + " " + genres.get(1)[1];
        };
    }

    /** E.g. ("cozy", 878, 10749) → "Cozy Sci-Fi Romantic"; null if any part is unknown. */
    public static String persona(String topMood, int topGenre, int secondGenre) {
        String mood = MOOD_WORDS.get(topMood);
        String[] first = GENRE_WORDS.get(topGenre);
        String[] second = GENRE_WORDS.get(secondGenre);
        if (mood == null || first == null || second == null) {
            return null;
        }
        return mood + " " + first[0] + " " + second[1];
    }
}
