import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type {
  CalibrationRatingDTO,
  FavoriteDTO,
  FavoriteMovieDTO,
  GenrePrefDTO,
  MoodPrefDTO,
  MovieSummaryDTO,
  OnboardingStateDTO,
  ProfileResponseDTO,
  UpdateOnboardingStepDTO,
} from '../types/api';
import { markTasteRebuilding } from './taste';

/** Calibration posters per batch. */
export const CALIBRATION_BATCH_SIZE = 10;

export const fetchOnboardingState = () => apiClient.get<OnboardingStateDTO>('/me/onboarding');

export const fetchCalibrationBatch = (count: number = CALIBRATION_BATCH_SIZE) =>
  apiClient.get<MovieSummaryDTO[]>(withQuery('/me/calibration/next', { count }));

/** Everything the user has saved during onboarding (resumable from `step`). */
export function useOnboardingState(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.onboarding,
    queryFn: fetchOnboardingState,
    enabled: options.enabled ?? true,
  });
}

/** Records the step the user is now on (server keeps the highest), mirrored into the `me` profile. */
export function useSetOnboardingStep() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (step: number) => {
      const body: UpdateOnboardingStepDTO = { step };
      return apiClient.put<OnboardingStateDTO>('/me/onboarding/step', body);
    },
    onSuccess: (state) => {
      qc.setQueryData(queryKeys.onboarding, state);
      qc.setQueryData<ProfileResponseDTO>(queryKeys.me, (p) => (p ? { ...p, onboardingStep: state.step } : p));
    },
  });
}

/** Replaces every stated genre preference (onboarding step 2 and the Taste editor). */
export function useSaveGenrePrefs() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (prefs: GenrePrefDTO[]) => apiClient.put<GenrePrefDTO[]>('/me/preferences/genres', prefs),
    onSuccess: (genrePrefs) => {
      qc.setQueryData<OnboardingStateDTO>(queryKeys.onboarding, (s) => (s ? { ...s, genrePrefs } : s));
      markTasteRebuilding(qc);
    },
  });
}

/** Replaces every stated mood preference (onboarding step 5 and the Taste editor). */
export function useSaveMoodPrefs() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (prefs: MoodPrefDTO[]) => apiClient.put<MoodPrefDTO[]>('/me/preferences/moods', prefs),
    onSuccess: (moodPrefs) => {
      qc.setQueryData<OnboardingStateDTO>(queryKeys.onboarding, (s) => (s ? { ...s, moodPrefs } : s));
      markTasteRebuilding(qc);
    },
  });
}

/** Replaces the all-time favourites (0–10, ranked from 1). */
export function useSaveFavorites() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (favorites: FavoriteDTO[]) =>
      apiClient.put<FavoriteMovieDTO[]>('/me/preferences/favorites', favorites),
    onSuccess: (favorites) => {
      qc.setQueryData<OnboardingStateDTO>(queryKeys.onboarding, (s) => (s ? { ...s, favorites } : s));
      markTasteRebuilding(qc);
    },
  });
}

type CalibrationBatchOptions = { enabled?: boolean; count?: number };

/**
 * The `batch`-th set of calibration posters for one deck. Each batch is fetched once and kept while
 * the deck is open (the server excludes what was already answered, so a refetch would reshuffle the
 * cards under the user); it's dropped as soon as the deck closes.
 */
export function useCalibrationBatch(deck: string, batch: number, options: CalibrationBatchOptions = {}) {
  const count = options.count ?? CALIBRATION_BATCH_SIZE;
  return useQuery({
    queryKey: queryKeys.calibrationBatch(deck, batch),
    queryFn: () => fetchCalibrationBatch(count),
    enabled: options.enabled ?? true,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}

/** Sends one batch of calibration answers (scores become private ratings, the rest "haven't seen"). */
export function useSubmitCalibration() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (answers: CalibrationRatingDTO[]) => apiClient.post<void>('/me/ratings/batch', answers),
    onSuccess: (_void, answers) => {
      const rated = answers.filter((a) => !a.unseen).length;
      qc.setQueryData<OnboardingStateDTO>(queryKeys.onboarding, (s) =>
        s ? { ...s, calibrationRatedCount: s.calibrationRatedCount + rated } : s,
      );
      void qc.invalidateQueries({ queryKey: queryKeys.ratings });
      void qc.invalidateQueries({ queryKey: queryKeys.recommendations });
      markTasteRebuilding(qc);
    },
  });
}
