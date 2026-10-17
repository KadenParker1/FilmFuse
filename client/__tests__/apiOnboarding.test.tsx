jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { useCompleteOnboarding } from '../api/me';
import { useMoods } from '../api/moods';
import {
  CALIBRATION_BATCH_SIZE,
  useCalibrationBatch,
  useOnboardingState,
  useSaveFavorites,
  useSaveGenrePrefs,
  useSaveMoodPrefs,
  useSetOnboardingStep,
  useSubmitCalibration,
} from '../api/onboarding';
import { queryKeys } from '../lib/queryKeys';
import { makeMovie, makeOnboardingState, makeProfile, makeTasteProfile } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { OnboardingStateDTO, ProfileResponseDTO, TasteProfileDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const BASE = 'http://localhost:8080/api/v1';
const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});
const call = (i = 0) => ({ url: fetchMock.mock.calls[i][0] as string, init: fetchMock.mock.calls[i][1] as RequestInit });
const body = (i = 0) => JSON.parse(call(i).init.body as string);
type Client = ReturnType<typeof createTestQueryClient>;
const invalidated = (client: Client, key: readonly unknown[]) => client.getQueryState(key)?.isInvalidated ?? false;

/** A client holding an onboarding state, a profile and a FRESH taste profile. */
function seeded(state: OnboardingStateDTO = makeOnboardingState()) {
  const client = createTestQueryClient();
  client.setQueryData(queryKeys.onboarding, state);
  client.setQueryData(queryKeys.me, makeProfile({ onboardingStep: state.step, onboardingCompletedAt: null }));
  client.setQueryData(queryKeys.tasteProfile, makeTasteProfile({ status: 'FRESH' }));
  client.setQueryData(queryKeys.recommendations, { pages: [{ items: [], nextCursor: null }], pageParams: [null] });
  client.setQueryData(queryKeys.myRatings, { pages: [{ items: [], nextCursor: null }], pageParams: [null] });
  return client;
}
const taste = (client: Client) => client.getQueryData<TasteProfileDTO>(queryKeys.tasteProfile);
const onboarding = (client: Client) => client.getQueryData<OnboardingStateDTO>(queryKeys.onboarding);

describe('useOnboardingState', () => {
  it('GETs /me/onboarding', async () => {
    const state = makeOnboardingState({ step: 3 });
    fetchMock.mockResolvedValueOnce(jsonResponse(200, state));
    const { result } = await renderHookWithClient(() => useOnboardingState());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/me/onboarding`);
    expect(call().init.method).toBeUndefined();
    expect(result.current.data).toEqual(state);
  });

  it('does nothing while disabled', async () => {
    await renderHookWithClient(() => useOnboardingState({ enabled: false }));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('useSetOnboardingStep', () => {
  it('PUTs the step and mirrors the returned step into onboarding and me', async () => {
    const client = seeded(makeOnboardingState({ step: 2 }));
    const answer = makeOnboardingState({ step: 5 });
    fetchMock.mockResolvedValueOnce(jsonResponse(200, answer));
    const { result } = await renderHookWithClient(() => useSetOnboardingStep(), client);
    await act(async () => {
      await result.current.mutateAsync(3);
    });
    expect(call().url).toBe(`${BASE}/me/onboarding/step`);
    expect(call().init.method).toBe('PUT');
    expect(body()).toEqual({ step: 3 });
    // The server keeps the highest step; the cache follows its answer, not the request.
    expect(onboarding(client)).toEqual(answer);
    expect(client.getQueryData<ProfileResponseDTO>(queryKeys.me)?.onboardingStep).toBe(5);
  });

  it('leaves the caches alone on failure', async () => {
    const client = seeded(makeOnboardingState({ step: 2 }));
    fetchMock.mockResolvedValueOnce(jsonResponse(400, { message: 'step must be 0–8' }));
    const { result } = await renderHookWithClient(() => useSetOnboardingStep(), client);
    await act(async () => {
      await expect(result.current.mutateAsync(9)).rejects.toThrow('step must be 0–8');
    });
    expect(onboarding(client)?.step).toBe(2);
  });
});

describe('preference saves', () => {
  it('useSaveGenrePrefs PUTs the list, caches it and marks taste rebuilding', async () => {
    const client = seeded();
    const prefs = [
      { genreId: 28, score: 9 },
      { genreId: 35, score: 2 },
    ];
    fetchMock.mockResolvedValue(jsonResponse(200, prefs));
    const { result } = await renderHookWithClient(() => useSaveGenrePrefs(), client);
    await act(async () => {
      await result.current.mutateAsync(prefs);
    });
    expect(call().url).toBe(`${BASE}/me/preferences/genres`);
    expect(call().init.method).toBe('PUT');
    expect(body()).toEqual(prefs);
    expect(onboarding(client)?.genrePrefs).toEqual(prefs);
    expect(taste(client)?.status).toBe('REBUILDING');
  });

  it('useSaveMoodPrefs PUTs the list and caches it', async () => {
    const client = seeded();
    const prefs = [{ moodTag: 'cozy', score: 8 }];
    fetchMock.mockResolvedValue(jsonResponse(200, prefs));
    const { result } = await renderHookWithClient(() => useSaveMoodPrefs(), client);
    await act(async () => {
      await result.current.mutateAsync(prefs);
    });
    expect(call().url).toBe(`${BASE}/me/preferences/moods`);
    expect(call().init.method).toBe('PUT');
    expect(body()).toEqual(prefs);
    expect(onboarding(client)?.moodPrefs).toEqual(prefs);
    expect(taste(client)?.status).toBe('REBUILDING');
  });

  it('useSaveFavorites PUTs ranks and caches the returned movies', async () => {
    const client = seeded();
    const favorites = [{ rank: 1, movie: makeMovie({ id: 5 }) }];
    fetchMock.mockResolvedValue(jsonResponse(200, favorites));
    const { result } = await renderHookWithClient(() => useSaveFavorites(), client);
    await act(async () => {
      await result.current.mutateAsync([{ movieId: 5, rank: 1 }]);
    });
    expect(call().url).toBe(`${BASE}/me/preferences/favorites`);
    expect(call().init.method).toBe('PUT');
    expect(body()).toEqual([{ movieId: 5, rank: 1 }]);
    expect(onboarding(client)?.favorites).toEqual(favorites);
    expect(taste(client)?.status).toBe('REBUILDING');
  });

  it('does not mark taste rebuilding while there is no vector yet (status stays NONE)', async () => {
    const client = seeded();
    client.setQueryData(queryKeys.tasteProfile, makeTasteProfile({ status: 'NONE' }));
    fetchMock.mockResolvedValue(jsonResponse(200, []));
    const { result } = await renderHookWithClient(() => useSaveGenrePrefs(), client);
    await act(async () => {
      await result.current.mutateAsync([]);
    });
    expect(taste(client)?.status).toBe('NONE');
    expect(invalidated(client, queryKeys.tasteProfile)).toBe(true);
  });

  it('surfaces server validation errors without touching the cache', async () => {
    const client = seeded(makeOnboardingState({ genrePrefs: [{ genreId: 28, score: 9 }] }));
    fetchMock.mockResolvedValue(jsonResponse(400, { message: 'Unknown genre 99999' }));
    const { result } = await renderHookWithClient(() => useSaveGenrePrefs(), client);
    await act(async () => {
      await expect(result.current.mutateAsync([{ genreId: 99999, score: 5 }])).rejects.toThrow('Unknown genre 99999');
    });
    expect(onboarding(client)?.genrePrefs).toEqual([{ genreId: 28, score: 9 }]);
    expect(taste(client)?.status).toBe('FRESH');
  });
});

describe('useCalibrationBatch', () => {
  it('GETs a batch of 10 by default, keyed per deck and batch', async () => {
    const movies = [makeMovie({ id: 1 }), makeMovie({ id: 2 })];
    fetchMock.mockResolvedValue(jsonResponse(200, movies));
    const client = createTestQueryClient();
    const { result } = await renderHookWithClient(() => useCalibrationBatch('onboarding', 0), client);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/me/calibration/next?count=${CALIBRATION_BATCH_SIZE}`);
    expect(client.getQueryData(queryKeys.calibrationBatch('onboarding', 0))).toEqual(movies);
    expect(client.getQueryData(queryKeys.calibrationBatch('onboarding', 1))).toBeUndefined();
  });

  it('takes a custom count and can be disabled', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, []));
    const { result } = await renderHookWithClient(() => useCalibrationBatch('d', 2, { count: 25 }));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/me/calibration/next?count=25`);
    fetchMock.mockClear();
    await renderHookWithClient(() => useCalibrationBatch('d', 3, { enabled: false }));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('never refetches a dealt batch (cards must not reshuffle under the user)', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, [makeMovie()]));
    const client = createTestQueryClient();
    const { result } = await renderHookWithClient(() => useCalibrationBatch('onboarding', 0), client);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(client.getQueryCache().find({ queryKey: queryKeys.calibrationBatch('onboarding', 0) })?.isStale()).toBe(false);
  });
});

describe('useSubmitCalibration', () => {
  it('POSTs the answers, counts the rated ones and refreshes ratings, picks and taste', async () => {
    const client = seeded(makeOnboardingState({ calibrationRatedCount: 4 }));
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const { result } = await renderHookWithClient(() => useSubmitCalibration(), client);
    const answers = [
      { movieId: 1, score: 8, unseen: false },
      { movieId: 2, score: null, unseen: true },
      { movieId: 3, score: 3, unseen: false },
    ];
    await act(async () => {
      await result.current.mutateAsync(answers);
    });
    expect(call().url).toBe(`${BASE}/me/ratings/batch`);
    expect(call().init.method).toBe('POST');
    expect(body()).toEqual(answers);
    expect(onboarding(client)?.calibrationRatedCount).toBe(6);
    expect(invalidated(client, queryKeys.myRatings)).toBe(true);
    expect(invalidated(client, queryKeys.recommendations)).toBe(true);
    expect(taste(client)?.status).toBe('REBUILDING');
  });
});

describe('useMoods', () => {
  it('GETs /moods once and keeps them', async () => {
    const moods = [{ tag: 'cozy', label: 'Cozy' }];
    fetchMock.mockResolvedValue(jsonResponse(200, moods));
    const client = createTestQueryClient();
    const { result } = await renderHookWithClient(() => useMoods(), client);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/moods`);
    expect(result.current.data).toEqual(moods);
    expect(client.getQueryCache().find({ queryKey: queryKeys.moods })?.isStale()).toBe(false);
  });
});

describe('useCompleteOnboarding', () => {
  it('caches the profile and refreshes onboarding, taste and picks', async () => {
    const client = seeded();
    const done = makeProfile({ onboardingCompletedAt: '2026-10-16T00:00:00Z' });
    fetchMock.mockResolvedValue(jsonResponse(200, done));
    const { result } = await renderHookWithClient(() => useCompleteOnboarding(), client);
    await act(async () => {
      await result.current.mutateAsync();
    });
    expect(call().url).toBe(`${BASE}/me/onboarding/complete`);
    expect(client.getQueryData(queryKeys.me)).toEqual(done);
    expect(invalidated(client, queryKeys.onboarding)).toBe(true);
    expect(invalidated(client, queryKeys.tasteProfile)).toBe(true);
    expect(invalidated(client, queryKeys.recommendations)).toBe(true);
  });

  it('rejects with the server message when minimums are missing', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { message: 'Pick at least 3 favourites first' }));
    const { result } = await renderHookWithClient(() => useCompleteOnboarding());
    await act(async () => {
      await expect(result.current.mutateAsync()).rejects.toThrow('Pick at least 3 favourites first');
    });
  });
});
