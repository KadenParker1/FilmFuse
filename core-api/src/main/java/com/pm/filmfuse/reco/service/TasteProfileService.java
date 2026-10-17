package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.movie.service.GenreService;
import com.pm.filmfuse.reco.dto.TasteDimensionDTO;
import com.pm.filmfuse.reco.dto.TasteHistoryEntryDTO;
import com.pm.filmfuse.reco.dto.TasteProfileDTO;
import com.pm.filmfuse.reco.dto.VectorStatus;
import com.pm.filmfuse.reco.model.PersonaTemplates;
import com.pm.filmfuse.reco.model.StoredVector;
import com.pm.filmfuse.reco.model.TasteDimensions;
import com.pm.filmfuse.reco.repository.UserVectorRepository;
import com.pm.filmfuse.user.dto.GenrePrefDTO;
import com.pm.filmfuse.user.dto.MoodPrefDTO;
import com.pm.filmfuse.user.service.TastePreferencesService;
import com.pm.filmfuse.rating.service.RatingService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's taste profile (stated vs learned per dimension) and vector history. Read-only
 * presentation of what the vector pipeline stored - no taste logic: {@code learned} is the stored
 * interpretable value, rounded to one decimal.
 */
@Service
public class TasteProfileService {

    public static final int TOP_GENRES = 5;
    public static final int HISTORY_TOP_GENRES = 3;

    private final UserVectorRepository vectors;
    private final TastePreferencesService preferences;
    private final RatingService ratings;
    private final GenreService genres;

    public TasteProfileService(UserVectorRepository vectors, TastePreferencesService preferences,
                               RatingService ratings, GenreService genres) {
        this.vectors = vectors;
        this.preferences = preferences;
        this.ratings = ratings;
        this.genres = genres;
    }

    @Transactional(readOnly = true)
    public TasteProfileDTO profile(UUID userId) {
        Optional<StoredVector> row = vectors.find(userId);
        List<Double> learned = row.filter(StoredVector::isBuilt).map(StoredVector::interpretable).orElse(null);
        Map<Integer, Integer> statedGenres = preferences.genrePrefs(userId).stream()
                .collect(Collectors.toMap(GenrePrefDTO::genreId, GenrePrefDTO::score));
        Map<String, Integer> statedMoods = preferences.moodPrefs(userId).stream()
                .collect(Collectors.toMap(MoodPrefDTO::moodTag, MoodPrefDTO::score));

        Map<Integer, String> genreNames = genres.namesById();
        List<TasteDimensionDTO> genreDims = new ArrayList<>();
        for (int genreId : TasteDimensions.GENRE_IDS) {
            genreDims.add(new TasteDimensionDTO(TasteDimensions.genreKey(genreId),
                    genreNames.getOrDefault(genreId, String.valueOf(genreId)), statedGenres.get(genreId),
                    learnedAt(learned, TasteDimensions.genreIndex(genreId))));
        }
        List<TasteDimensionDTO> moodDims = new ArrayList<>();
        for (String tag : TasteDimensions.MOOD_TAGS) {
            moodDims.add(new TasteDimensionDTO(TasteDimensions.moodKey(tag), TasteDimensions.MOOD_LABELS.get(tag),
                    statedMoods.get(tag), learnedAt(learned, TasteDimensions.moodIndex(tag))));
        }
        List<TasteDimensionDTO> dimensions = new ArrayList<>(genreDims);
        dimensions.addAll(moodDims);

        return new TasteProfileDTO(
                row.map(StoredVector::status).orElse(VectorStatus.NONE),
                row.map(StoredVector::updatedAt).orElse(null),
                row.filter(StoredVector::isBuilt).map(StoredVector::modelVersion).orElse(null),
                learned == null ? null : persona(genreDims, moodDims),
                top(genreDims, TOP_GENRES),
                dimensions,
                ratings.count(userId));
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<TasteHistoryEntryDTO> history(UUID userId, int limit) {
        Map<Integer, String> genreNames = genres.namesById();
        return vectors.history(userId, limit).stream().map(entry -> {
            List<TasteDimensionDTO> dims = TasteDimensions.GENRE_IDS.stream()
                    .map(id -> new TasteDimensionDTO(TasteDimensions.genreKey(id),
                            genreNames.getOrDefault(id, String.valueOf(id)), null,
                            learnedAt(entry.interpretable(), TasteDimensions.genreIndex(id))))
                    .toList();
            return new TasteHistoryEntryDTO(entry.version(), entry.reason(), entry.modelVersion(), entry.createdAt(),
                    top(dims, HISTORY_TOP_GENRES));
        }).toList();
    }

    /**
     * At most {@code n} dims whose value (learned when present, else stated) is above neutral
     * ({@link TasteDimensions#NEUTRAL_SCORE}), highest first - so possibly fewer than {@code n}, or
     * none. Ties keep the fixed vector order.
     */
    static List<TasteDimensionDTO> top(List<TasteDimensionDTO> dims, int n) {
        return dims.stream()
                .filter(d -> value(d) != null && value(d) > TasteDimensions.NEUTRAL_SCORE)
                .sorted(Comparator.comparingDouble((TasteDimensionDTO d) -> value(d)).reversed())
                .limit(n)
                .toList();
    }

    private static Double value(TasteDimensionDTO dim) {
        if (dim.learned() != null) {
            return dim.learned();
        }
        return dim.stated() == null ? null : dim.stated().doubleValue();
    }

    /** Learned values only; genres and mood above neutral, with fallbacks in {@link PersonaTemplates}. */
    private static String persona(List<TasteDimensionDTO> genreDims, List<TasteDimensionDTO> moodDims) {
        List<Integer> topGenres = genreDims.stream().filter(TasteProfileService::learnedAboveNeutral)
                .sorted(Comparator.comparingDouble(TasteDimensionDTO::learned).reversed()).limit(2)
                .map(d -> Integer.parseInt(d.key().substring("genre:".length()))).toList();
        String topMood = moodDims.stream().filter(TasteProfileService::learnedAboveNeutral)
                .max(Comparator.comparingDouble(TasteDimensionDTO::learned)
                        .thenComparing(Comparator.comparingInt(moodDims::indexOf).reversed()))
                .map(d -> d.key().substring("mood:".length())).orElse(null);
        return PersonaTemplates.persona(topMood, topGenres);
    }

    private static boolean learnedAboveNeutral(TasteDimensionDTO dim) {
        return dim.learned() != null && dim.learned() > TasteDimensions.NEUTRAL_SCORE;
    }

    private static Double learnedAt(List<Double> interpretable, int index) {
        if (interpretable == null || index < 0 || index >= interpretable.size() || interpretable.get(index) == null) {
            return null;
        }
        return Math.round(interpretable.get(index) * 10.0) / 10.0;
    }
}
