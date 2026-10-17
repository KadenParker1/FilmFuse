package com.pm.filmfuse.user.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.service.MovieService;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.reco.dto.VectorChangeReason;
import com.pm.filmfuse.reco.model.TasteDimensions;
import com.pm.filmfuse.reco.service.VectorDirtyMarker;
import com.pm.filmfuse.user.dto.FavoriteDTO;
import com.pm.filmfuse.user.dto.FavoriteMovieDTO;
import com.pm.filmfuse.user.dto.GenrePrefDTO;
import com.pm.filmfuse.user.dto.MoodDTO;
import com.pm.filmfuse.user.dto.MoodPrefDTO;
import com.pm.filmfuse.user.repository.TastePreferencesRepository;
import com.pm.filmfuse.user.repository.TastePreferencesRepository.Favorite;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stated taste - genre and mood scores (1-10) and all-time favourites. Used by onboarding and the
 * Taste editor alike; every write replaces the whole list and marks the vector dirty
 * ({@code TASTE_EDIT}) in the same transaction. Lists come back in the fixed vector order
 * (favourites by rank).
 */
@Service
public class TastePreferencesService {

    public static final int MAX_FAVORITES = 10;

    static final String SCORE_RANGE = "Every score must be a whole number from 1 to 10.";
    static final String UNKNOWN_GENRE = "Unknown genre: ";
    static final String DUPLICATE_GENRE = "Each genre can appear only once.";
    static final String UNKNOWN_MOOD = "Unknown mood: ";
    static final String DUPLICATE_MOOD = "Each mood can appear only once.";
    static final String TOO_MANY_FAVORITES = "Pick at most " + MAX_FAVORITES + " favourites.";
    static final String FAVORITE_FIELDS = "Each favourite needs a movieId and a rank of 1 or more.";
    static final String DUPLICATE_FAVORITE = "Each movie and each rank can appear only once.";
    static final String NULL_ITEM = "The list cannot contain null items.";
    static final String TOO_MANY_GENRES = "Send at most one score per genre.";
    static final String TOO_MANY_MOODS = "Send at most one score per mood.";

    private final TastePreferencesRepository repository;
    private final VectorDirtyMarker dirtyMarker;
    private final MovieService movieService;
    private final MovieSummaries movieSummaries;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public TastePreferencesService(TastePreferencesRepository repository, VectorDirtyMarker dirtyMarker,
                                   MovieService movieService, MovieSummaries movieSummaries,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.dirtyMarker = dirtyMarker;
        this.movieService = movieService;
        this.movieSummaries = movieSummaries;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    /** The mood dimensions, in the fixed vector order. */
    public static List<MoodDTO> moods() {
        return TasteDimensions.MOOD_TAGS.stream().map(tag -> new MoodDTO(tag, TasteDimensions.MOOD_LABELS.get(tag))).toList();
    }

    // ------------------------------------------------------------------------------- reads

    public List<GenrePrefDTO> genrePrefs(UUID userId) {
        return readTx.execute(status -> toGenreDTOs(repository.genrePrefs(userId)));
    }

    public List<MoodPrefDTO> moodPrefs(UUID userId) {
        return readTx.execute(status -> toMoodDTOs(repository.moodPrefs(userId)));
    }

    public List<FavoriteMovieDTO> favorites(UUID userId) {
        return readTx.execute(status -> toFavoriteDTOs(repository.favorites(userId)));
    }

    // ------------------------------------------------------------------------------- writes

    /** 400 on a null item, an unknown genre, a duplicate or a score outside 1-10. */
    public List<GenrePrefDTO> replaceGenrePrefs(UUID userId, List<GenrePrefDTO> request) {
        Map<Integer, Integer> prefs = new LinkedHashMap<>();
        if (requireList(request).size() > TasteDimensions.GENRE_IDS.size()) {
            throw new BadRequestException(TOO_MANY_GENRES);
        }
        for (GenrePrefDTO pref : request) {
            if (pref == null) {
                throw new BadRequestException(NULL_ITEM);
            }
            if (pref.genreId() == null || !TasteDimensions.isGenre(pref.genreId())) {
                throw new BadRequestException(UNKNOWN_GENRE + pref.genreId());
            }
            requireScore(pref.score());
            if (prefs.put(pref.genreId(), pref.score()) != null) {
                throw new BadRequestException(DUPLICATE_GENRE);
            }
        }
        return tx.execute(status -> {
            repository.lockUser(userId);
            repository.replaceGenrePrefs(userId, prefs, clock.instant());
            dirtyMarker.markDirty(userId, VectorChangeReason.TASTE_EDIT);
            return toGenreDTOs(prefs);
        });
    }

    /** 400 on a null item, an unknown tag (see {@code GET /moods}), a duplicate or a score outside 1-10. */
    public List<MoodPrefDTO> replaceMoodPrefs(UUID userId, List<MoodPrefDTO> request) {
        Map<String, Integer> prefs = new LinkedHashMap<>();
        if (requireList(request).size() > TasteDimensions.MOOD_TAGS.size()) {
            throw new BadRequestException(TOO_MANY_MOODS);
        }
        for (MoodPrefDTO pref : request) {
            if (pref == null) {
                throw new BadRequestException(NULL_ITEM);
            }
            if (!TasteDimensions.isMood(pref.moodTag())) {
                throw new BadRequestException(UNKNOWN_MOOD + echo(pref.moodTag()));
            }
            requireScore(pref.score());
            if (prefs.put(pref.moodTag(), pref.score()) != null) {
                throw new BadRequestException(DUPLICATE_MOOD);
            }
        }
        return tx.execute(status -> {
            repository.lockUser(userId);
            repository.replaceMoodPrefs(userId, prefs, clock.instant());
            dirtyMarker.markDirty(userId, VectorChangeReason.TASTE_EDIT);
            return toMoodDTOs(prefs);
        });
    }

    /**
     * 0-{@value #MAX_FAVORITES} favourites with unique movies and unique ranks (1+). A movie not yet
     * cached is fetched from the catalog first (404 when the catalog does not know it).
     */
    public List<FavoriteMovieDTO> replaceFavorites(UUID userId, List<FavoriteDTO> request) {
        List<FavoriteDTO> items = requireList(request);
        if (items.size() > MAX_FAVORITES) {
            throw new BadRequestException(TOO_MANY_FAVORITES);
        }
        Set<Integer> movies = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();
        for (FavoriteDTO favorite : items) {
            if (favorite == null) {
                throw new BadRequestException(NULL_ITEM);
            }
            if (favorite.movieId() == null || favorite.movieId() < 1 || favorite.rank() == null || favorite.rank() < 1) {
                throw new BadRequestException(FAVORITE_FIELDS);
            }
            if (!movies.add(favorite.movieId()) || !ranks.add(favorite.rank())) {
                throw new BadRequestException(DUPLICATE_FAVORITE);
            }
        }
        // Catalog calls happen before the transaction, so it never waits on TMDB.
        movieService.ensureCached(movies);
        List<Favorite> favorites = items.stream()
                .map(f -> new Favorite(f.rank(), f.movieId()))
                .sorted(Comparator.comparingInt(Favorite::rank))
                .toList();
        return tx.execute(status -> {
            repository.lockUser(userId);
            repository.replaceFavorites(userId, favorites, clock.instant());
            dirtyMarker.markDirty(userId, VectorChangeReason.TASTE_EDIT);
            return toFavoriteDTOs(favorites);
        });
    }

    // ------------------------------------------------------------------------------- helpers

    private static <T> List<T> requireList(List<T> request) {
        if (request == null) {
            throw new BadRequestException("The request body must be a JSON array.");
        }
        return request;
    }

    /** Caller text quoted back in an error, cut short so a huge value is not reflected whole. */
    static String echo(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() > 40 ? value.substring(0, 40) + "..." : value;
    }

    static void requireScore(Integer score) {
        if (score == null || score < 1 || score > 10) {
            throw new BadRequestException(SCORE_RANGE);
        }
    }

    static List<GenrePrefDTO> toGenreDTOs(Map<Integer, Integer> prefs) {
        return TasteDimensions.GENRE_IDS.stream().filter(prefs::containsKey)
                .map(id -> new GenrePrefDTO(id, prefs.get(id))).toList();
    }

    static List<MoodPrefDTO> toMoodDTOs(Map<String, Integer> prefs) {
        return TasteDimensions.MOOD_TAGS.stream().filter(prefs::containsKey)
                .map(tag -> new MoodPrefDTO(tag, prefs.get(tag))).toList();
    }

    private List<FavoriteMovieDTO> toFavoriteDTOs(List<Favorite> favorites) {
        Map<Integer, MovieSummaryDTO> movies = movieSummaries.of(favorites.stream().map(Favorite::movieId).toList());
        return favorites.stream()
                .filter(f -> movies.containsKey(f.movieId()))
                .map(f -> new FavoriteMovieDTO(f.rank(), movies.get(f.movieId())))
                .toList();
    }
}
