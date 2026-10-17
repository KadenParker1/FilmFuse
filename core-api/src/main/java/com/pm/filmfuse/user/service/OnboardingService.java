package com.pm.filmfuse.user.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.rating.service.RatingService;
import com.pm.filmfuse.reco.dto.VectorChangeReason;
import com.pm.filmfuse.reco.service.VectorBuildTrigger;
import com.pm.filmfuse.reco.service.VectorDirtyMarker;
import com.pm.filmfuse.user.dto.FavoriteMovieDTO;
import com.pm.filmfuse.user.dto.GenrePrefDTO;
import com.pm.filmfuse.user.dto.OnboardingStateDTO;
import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.mapper.ProfileMapper;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Onboarding progress (8 resumable steps; each step's data is saved through its own endpoint) and
 * completion.
 *
 * <p>Completion requires a username, at least {@value #MIN_GENRE_PREFS} genre scores with at least
 * {@value #MIN_LOVED_GENRES} of them {@value #LOVED_SCORE}+, and at least {@value #MIN_FAVORITES}
 * favourites. It is idempotent, and a profile that is already complete (e.g. through the interim
 * Phase 0 flow, before taste steps existed) is never re-checked, so those users keep access.
 * The first completion marks the vector dirty ({@code ONBOARDING}) and, after commit, starts the
 * build in the background ({@link VectorBuildTrigger}) without waiting for it: the taste profile
 * reports {@code REBUILDING} until it is done (the client polls). A busy executor or a failed build
 * leaves the row dirty for the scheduler.
 */
@Service
public class OnboardingService {

    static final String USERNAME_REQUIRED = "Choose a username first.";
    static final String GENRES_REQUIRED = "Score at least 5 genres, with at least 3 of them 8 or higher.";
    static final String FAVORITES_REQUIRED = "Pick at least 3 all-time favourites.";

    /** The last step (taste reveal); completion moves the step here. */
    public static final int FINAL_STEP = 8;
    static final int MIN_GENRE_PREFS = 5;
    static final int MIN_LOVED_GENRES = 3;
    static final int LOVED_SCORE = 8;
    static final int MIN_FAVORITES = 3;

    private final ProfileRepository profileRepository;
    private final TastePreferencesService preferences;
    private final RatingService ratingService;
    private final ComfortSettingsService comfortSettings;
    private final VectorDirtyMarker dirtyMarker;
    private final VectorBuildTrigger vectorBuildTrigger;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OnboardingService(ProfileRepository profileRepository, TastePreferencesService preferences,
                             RatingService ratingService, ComfortSettingsService comfortSettings,
                             VectorDirtyMarker dirtyMarker, VectorBuildTrigger vectorBuildTrigger,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.profileRepository = profileRepository;
        this.preferences = preferences;
        this.ratingService = ratingService;
        this.comfortSettings = comfortSettings;
        this.dirtyMarker = dirtyMarker;
        this.vectorBuildTrigger = vectorBuildTrigger;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public OnboardingStateDTO state(Profile profile) {
        return new OnboardingStateDTO(
                profile.getOnboardingStep(),
                profile.getOnboardingCompletedAt() != null,
                preferences.genrePrefs(profile.getId()),
                preferences.moodPrefs(profile.getId()),
                preferences.favorites(profile.getId()),
                ratingService.calibrationRatedCount(profile.getId()),
                comfortSettings.get(profile.getId()));
    }

    /** The step only moves forward: a lower value is ignored (no error). */
    public OnboardingStateDTO updateStep(Profile profile, int step) {
        if (step > profile.getOnboardingStep()) {
            profile.setOnboardingStep(step);
            profile = profileRepository.saveAndFlush(profile);
        }
        return state(profile);
    }

    /** See the class comment. 400 with a sentence naming the first missing requirement. */
    public ProfileResponseDTO complete(Profile profile) {
        if (profile.getOnboardingCompletedAt() != null) {
            return ProfileMapper.toDTO(profile);
        }
        if (profile.getUsername() == null) {
            throw new BadRequestException(USERNAME_REQUIRED);
        }
        List<GenrePrefDTO> genres = preferences.genrePrefs(profile.getId());
        long loved = genres.stream().filter(p -> p.score() >= LOVED_SCORE).count();
        if (genres.size() < MIN_GENRE_PREFS || loved < MIN_LOVED_GENRES) {
            throw new BadRequestException(GENRES_REQUIRED);
        }
        List<FavoriteMovieDTO> favorites = preferences.favorites(profile.getId());
        if (favorites.size() < MIN_FAVORITES) {
            throw new BadRequestException(FAVORITES_REQUIRED);
        }

        Profile completing = profile;
        Profile saved = tx.execute(status -> {
            // Postgres keeps microseconds; truncating keeps the response equal to what a re-read returns.
            completing.setOnboardingCompletedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
            completing.setOnboardingStep(Math.max(completing.getOnboardingStep(), FINAL_STEP));
            Profile result = profileRepository.saveAndFlush(completing);
            dirtyMarker.markDirty(result.getId(), VectorChangeReason.ONBOARDING);
            return result;
        });
        // After commit, in the background: the response never waits on the ML service.
        vectorBuildTrigger.requestRebuild(saved.getId());
        return ProfileMapper.toDTO(saved);
    }
}
