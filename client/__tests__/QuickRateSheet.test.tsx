jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { Pressable, Text } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { RateFab } from '../components/RateFab';
import {
  QuickRateProvider,
  TASTE_UPDATED,
  TASTE_UPDATING,
  TASTE_WAIT_MAX_MS,
  TOAST_HIDE_MS,
  useQuickRate,
} from '../components/rating/QuickRateProvider';
import { TASTE_POLL_MS } from '../api/taste';
import { serveRoutes, type Route } from '../test-utils/fetchRoutes';
import { makeMovie, makeRating, makeTasteProfile } from '../test-utils/fixtures';
import { renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  global.fetch = fetchMock as unknown as typeof fetch;
});
afterEach(() => jest.useRealTimers());

const heat = makeMovie({ id: 100, title: 'Heat', releaseYear: 1995, posterUrl: 'https://x/heat.jpg' });
const notRated: Route = { path: '/me/ratings/100', status: 404, body: { message: 'Not rated' } };
const tasteRoutes = (...statuses: ('REBUILDING' | 'FRESH' | 'NONE')[]): Route[] => [
  ...statuses.slice(0, -1).map((status) => ({ path: '/me/taste-profile', body: makeTasteProfile({ status }), times: 1 })),
  { path: '/me/taste-profile', body: makeTasteProfile({ status: statuses[statuses.length - 1] }) },
];

/** Buttons that open the sheet the way the app's entry points do. */
function Openers() {
  const { openQuickRate, openRatePicker } = useQuickRate();
  return (
    <>
      <Pressable accessibilityRole="button" accessibilityLabel="open heat" onPress={() => openQuickRate(heat)}>
        <Text>open heat</Text>
      </Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel="open picker" onPress={openRatePicker}>
        <Text>open picker</Text>
      </Pressable>
    </>
  );
}

async function openHeat(routes: Route[]) {
  const api = serveRoutes(fetchMock, routes);
  const utils = await renderWithClient(
    <QuickRateProvider>
      <Openers />
    </QuickRateProvider>,
  );
  await fireEvent.press(screen.getByRole('button', { name: 'open heat' }));
  return { api, ...utils };
}

const score = (n: number) => screen.getByRole('radio', { name: new RegExp(`^${n} out of 10`) });
/** The sheet's body unmounts when it closes (the Modal itself lingers in jest without native dismiss events). */
const sheetOpen = () => screen.queryAllByRole('radio', { name: /out of 10/ }).length > 0;
const saveButton = () => screen.getByRole('button', { name: /Save rating|Update rating/ });

describe('QuickRateSheet: a new rating', () => {
  it('shows the poster, needs a score before saving and labels the chosen score', async () => {
    await openHeat([notRated]);
    await waitFor(() => expect(screen.getByText('Heat')).toBeOnTheScreen());
    expect(screen.getByRole('header', { name: 'Quick rate' })).toBeOnTheScreen();
    expect(screen.getByTestId('quick-rate-poster')).toBeOnTheScreen();
    expect(screen.getAllByRole('radio', { name: /out of 10/ })).toHaveLength(10);
    expect(screen.getByTestId('score-label')).toHaveTextContent('Tap a score');
    expect(saveButton()).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Remove rating' })).toBeNull();

    await fireEvent.press(score(8));
    expect(score(8)).toBeChecked();
    expect(screen.getByTestId('score-label')).toHaveTextContent('8/10 · really like');
    await fireEvent.press(score(2));
    expect(screen.getByTestId('score-label')).toHaveTextContent("2/10 · can't stand");
    expect(saveButton()).toBeEnabled();
  });

  it('saves a friends-visible quick rate, closes and shows "Taste updating…"', async () => {
    const { api } = await openHeat([
      notRated,
      { method: 'PUT', path: '/ratings/100', body: makeRating({ score: 9 }) },
      ...tasteRoutes('REBUILDING'),
    ]);
    await waitFor(() => expect(score(9)).toBeOnTheScreen());
    await fireEvent.press(score(9));
    await fireEvent.press(saveButton());
    await waitFor(() => expect(sheetOpen()).toBe(false));
    expect(api.callsTo('PUT', '/ratings/100')[0]?.body).toEqual({
      score: 9,
      body: null,
      containsSpoilers: false,
      visibility: 'FRIENDS',
      source: 'QUICK_RATE',
    });
    expect(screen.getByTestId('toast')).toHaveTextContent(TASTE_UPDATING);
  });

  it('adds a note with a counter and spoiler flag, and posts publicly', async () => {
    const { api } = await openHeat([notRated, { method: 'PUT', path: '/ratings/100', body: makeRating() }, ...tasteRoutes('REBUILDING')]);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add a note' })).toBeOnTheScreen());
    expect(screen.queryByLabelText('Note')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Add a note' }));
    await fireEvent.changeText(screen.getByLabelText('Note'), '  Big heist  ');
    expect(screen.getByTestId('note-counter')).toHaveTextContent('13/1000');
    expect(screen.getByLabelText('Note').props.maxLength).toBe(1000);
    await fireEvent(screen.getByLabelText('Contains spoilers'), 'valueChange', true);
    await fireEvent.press(screen.getByRole('radio', { name: 'Public' }));
    await fireEvent.press(score(10));
    await fireEvent.press(saveButton());
    await waitFor(() => expect(api.callsTo('PUT', '/ratings/100')).toHaveLength(1));
    expect(api.callsTo('PUT', '/ratings/100')[0]?.body).toEqual({
      score: 10,
      body: 'Big heist',
      containsSpoilers: true,
      visibility: 'PUBLIC',
      source: 'QUICK_RATE',
    });
  });

  it('keeps it private when "Post to feed" is off (and drops the spoiler flag without a note)', async () => {
    const { api } = await openHeat([notRated, { method: 'PUT', path: '/ratings/100', body: makeRating() }, ...tasteRoutes('REBUILDING')]);
    await waitFor(() => expect(screen.getByLabelText('Post to feed')).toBeOnTheScreen());
    expect(screen.getByLabelText('Post to feed').props.value).toBe(true);
    expect(screen.getByRole('radio', { name: 'Friends' })).toBeChecked();
    await fireEvent(screen.getByLabelText('Post to feed'), 'valueChange', false);
    expect(screen.queryByRole('radio', { name: 'Public' })).toBeNull();
    expect(screen.getByText('Only you can see this rating.')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Add a note' }));
    await fireEvent(screen.getByLabelText('Contains spoilers'), 'valueChange', true);
    await fireEvent.press(score(4));
    await fireEvent.press(saveButton());
    await waitFor(() => expect(api.callsTo('PUT', '/ratings/100')).toHaveLength(1));
    expect(api.callsTo('PUT', '/ratings/100')[0]?.body).toMatchObject({
      score: 4,
      body: null,
      containsSpoilers: false,
      visibility: 'PRIVATE',
    });
  });

  it('shows server validation errors inline and stays open', async () => {
    await openHeat([
      notRated,
      { method: 'PUT', path: '/ratings/100', status: 400, body: { message: 'Invalid', fieldErrors: { body: 'Note is too long.' } } },
    ]);
    await waitFor(() => expect(score(6)).toBeOnTheScreen());
    await fireEvent.press(score(6));
    await fireEvent.press(saveButton());
    await waitFor(() => expect(screen.getByText('Note is too long.')).toBeOnTheScreen());
    expect(screen.getByRole('header', { name: 'Quick rate' })).toBeOnTheScreen();
    expect(screen.queryByTestId('toast')).toBeNull();
  });

  it('closes from the backdrop without saving', async () => {
    const { api } = await openHeat([notRated]);
    await waitFor(() => expect(score(6)).toBeOnTheScreen());
    await fireEvent.press(screen.getByLabelText('Close', { includeHiddenElements: true }));
    expect(sheetOpen()).toBe(false);
    expect(api.callsTo('PUT', '/ratings/100')).toHaveLength(0);
  });

  it('retries when the existing rating fails to load', async () => {
    const { api } = await openHeat([{ path: '/me/ratings/100', status: 500, body: { message: 'boom' }, times: 1 }, notRated]);
    await waitFor(() => expect(screen.getByText("Couldn't load your rating")).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(score(5)).toBeOnTheScreen());
    expect(api.callsTo('GET', '/me/ratings/100')).toHaveLength(2);
  });
});

describe('QuickRateSheet: re-rating', () => {
  const existing = makeRating({
    movie: heat,
    score: 7,
    body: 'Tense',
    containsSpoilers: true,
    visibility: 'PUBLIC',
    source: 'POST_WATCH',
  });

  it('starts from the existing rating and keeps its source', async () => {
    const { api } = await openHeat([
      { path: '/me/ratings/100', body: existing },
      { method: 'PUT', path: '/ratings/100', body: existing },
      ...tasteRoutes('REBUILDING'),
    ]);
    await waitFor(() => expect(screen.getByTestId('quick-rate-existing')).toHaveTextContent('You rated it 7/10'));
    expect(score(7)).toBeChecked();
    expect(screen.getByLabelText('Note').props.value).toBe('Tense');
    expect(screen.getByLabelText('Contains spoilers').props.value).toBe(true);
    expect(screen.getByRole('radio', { name: 'Public' })).toBeChecked();
    await fireEvent.press(score(8));
    await fireEvent.press(screen.getByRole('button', { name: 'Update rating' }));
    await waitFor(() => expect(api.callsTo('PUT', '/ratings/100')).toHaveLength(1));
    expect(api.callsTo('PUT', '/ratings/100')[0]?.body).toEqual({
      score: 8,
      body: 'Tense',
      containsSpoilers: true,
      visibility: 'PUBLIC',
      source: 'POST_WATCH',
    });
  });

  it('turns a calibration swipe into a private quick rate', async () => {
    const swipe = makeRating({ movie: heat, score: 6, visibility: 'PRIVATE', source: 'ONBOARDING_SWIPE' });
    const { api } = await openHeat([
      { path: '/me/ratings/100', body: swipe },
      { method: 'PUT', path: '/ratings/100', body: swipe },
      ...tasteRoutes('REBUILDING'),
    ]);
    await waitFor(() => expect(screen.getByLabelText('Post to feed').props.value).toBe(false));
    await fireEvent.press(screen.getByRole('button', { name: 'Update rating' }));
    await waitFor(() => expect(api.callsTo('PUT', '/ratings/100')).toHaveLength(1));
    expect(api.callsTo('PUT', '/ratings/100')[0]?.body).toMatchObject({ score: 6, visibility: 'PRIVATE', source: 'QUICK_RATE' });
  });

  it('removes the rating, closes and shows the toast', async () => {
    const { api } = await openHeat([
      { path: '/me/ratings/100', body: existing },
      { method: 'DELETE', path: '/ratings/100', status: 204 },
      ...tasteRoutes('REBUILDING'),
    ]);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Remove rating' })).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Remove rating' }));
    await waitFor(() => expect(sheetOpen()).toBe(false));
    expect(api.callsTo('DELETE', '/ratings/100')).toHaveLength(1);
    expect(screen.getByTestId('toast')).toHaveTextContent(TASTE_UPDATING);
  });

  it('shows a failed removal inline', async () => {
    await openHeat([
      { path: '/me/ratings/100', body: existing },
      { method: 'DELETE', path: '/ratings/100', status: 500, body: { message: 'boom' } },
    ]);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Remove rating' })).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Remove rating' }));
    await waitFor(() => expect(screen.getByText(/server had a problem/)).toBeOnTheScreen());
    expect(screen.getByRole('header', { name: 'Quick rate' })).toBeOnTheScreen();
  });
});

describe('QuickRateSheet: from "+ Rate" (movie search first)', () => {
  it('searches, picks a movie and rates it', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/movies/search?q=heat', body: { items: [heat], nextCursor: null } },
      notRated,
      { method: 'PUT', path: '/ratings/100', body: makeRating() },
      ...tasteRoutes('REBUILDING'),
    ]);
    await renderWithClient(
      <QuickRateProvider>
        <RateFab bottomOffset={80} />
      </QuickRateProvider>,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Rate a movie' }));
    expect(screen.getByRole('header', { name: 'Rate a movie' })).toBeOnTheScreen();
    expect(screen.getByTestId('movie-picker-hint')).toBeOnTheScreen();
    await fireEvent.changeText(screen.getByLabelText('Search movies to rate'), 'heat');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Rate Heat (1995)' })).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Rate Heat (1995)' }));
    await waitFor(() => expect(screen.getByRole('header', { name: 'Quick rate' })).toBeOnTheScreen());
    await waitFor(() => expect(score(7)).toBeOnTheScreen());
    await fireEvent.press(score(7));
    await fireEvent.press(saveButton());
    await waitFor(() => expect(api.callsTo('PUT', '/ratings/100')).toHaveLength(1));
  });

  it('says when nothing matches, and shows search failures', async () => {
    serveRoutes(fetchMock, [
      { path: '/movies/search?q=zzz', body: { items: [], nextCursor: null } },
      { path: '/movies/search?q=err', status: 500, body: { message: 'boom' } },
    ]);
    await renderWithClient(
      <QuickRateProvider>
        <RateFab bottomOffset={80} />
      </QuickRateProvider>,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Rate a movie' }));
    await fireEvent.changeText(screen.getByLabelText('Search movies to rate'), 'zzz');
    await waitFor(() => expect(screen.getByText('Nothing found for “zzz”.')).toBeOnTheScreen());
    await fireEvent.changeText(screen.getByLabelText('Search movies to rate'), 'err');
    await waitFor(() => expect(screen.getByText('Search failed')).toBeOnTheScreen());
  });
});

describe('Taste toast', () => {
  async function saveWithFakeTimers(routes: Route[]) {
    jest.useFakeTimers();
    const utils = await openHeat([notRated, { method: 'PUT', path: '/ratings/100', body: makeRating() }, ...routes]);
    await waitFor(() => expect(score(8)).toBeOnTheScreen());
    await fireEvent.press(score(8));
    await fireEvent.press(saveButton());
    await waitFor(() => expect(screen.getByTestId('toast')).toHaveTextContent(TASTE_UPDATING));
    return utils;
  }
  const advance = async (ms: number) => {
    await act(async () => {
      jest.advanceTimersByTime(ms);
    });
  };

  it('polls until the profile is FRESH, says "Taste updated", then hides', async () => {
    const { api } = await saveWithFakeTimers(tasteRoutes('REBUILDING', 'FRESH'));
    expect(screen.getByTestId('toast-spinner')).toBeOnTheScreen();
    await advance(TASTE_POLL_MS);
    await waitFor(() => expect(screen.getByTestId('toast')).toHaveTextContent(TASTE_UPDATED));
    expect(api.callsTo('GET', '/me/taste-profile').length).toBeGreaterThanOrEqual(2);
    await advance(TOAST_HIDE_MS);
    expect(screen.queryByTestId('toast')).toBeNull();
  });

  it('gives up quietly if the rebuild never lands', async () => {
    await saveWithFakeTimers(tasteRoutes('REBUILDING'));
    await advance(TASTE_WAIT_MAX_MS - 1);
    expect(screen.getByTestId('toast')).toHaveTextContent(TASTE_UPDATING);
    await advance(1);
    expect(screen.queryByTestId('toast')).toBeNull();
  });
});

describe('useQuickRate', () => {
  it('throws outside the provider', async () => {
    const spy = jest.spyOn(console, 'error').mockImplementation(() => {});
    await expect(render(<Openers />)).rejects.toThrow('useQuickRate must be used inside <QuickRateProvider>');
    spy.mockRestore();
  });
});
