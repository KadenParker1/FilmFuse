jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));
const mockShowTasteUpdating = jest.fn();
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openQuickRate: jest.fn(), openRatePicker: jest.fn(), showTasteUpdating: mockShowTasteUpdating }),
}));

import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import TasteEditorScreen from '../app/taste/index';
import TasteHistoryScreen from '../app/taste/history';
import RecalibrateScreen from '../app/taste/recalibrate';
import { PersonaCard } from '../components/taste/PersonaCard';
import { TasteSection } from '../components/taste/TasteSection';
import { serveRoutes } from '../test-utils/fetchRoutes';
import { makeDimension, makeHistoryEntry, makeMovie, makeTasteProfile } from '../test-utils/fixtures';
import { renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';
import { slideTo } from '../test-utils/slider';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  mockShowTasteUpdating.mockClear();
  global.fetch = fetchMock as unknown as typeof fetch;
});

describe('PersonaCard', () => {
  it('shows the persona, status, top genres with their values and the ratings basis', async () => {
    await render(
      <PersonaCard
        profile={makeTasteProfile({
          topGenres: [makeDimension(), makeDimension({ key: 'genre:18', label: 'Drama', stated: 6, learned: null })],
        })}
      />,
    );
    expect(screen.getByTestId('persona')).toHaveTextContent('Cozy Sci-Fi Romantic');
    expect(screen.getByTestId('taste-status')).toHaveTextContent('Up to date');
    expect(screen.getByTestId('top-genres')).toHaveTextContent(/Science Fiction7\.4/);
    // Falls back to the stated value without a learned one.
    expect(screen.getByTestId('top-genres')).toHaveTextContent(/Drama6/);
    expect(screen.getByText(/Based on 12 ratings/)).toBeOnTheScreen();
  });

  it('reads well before there is a persona or ratings', async () => {
    await render(<PersonaCard profile={makeTasteProfile({ status: 'REBUILDING', persona: null, topGenres: [], ratingsCount: 0 })} />);
    expect(screen.getByTestId('persona')).toHaveTextContent('Your taste is taking shape');
    expect(screen.getByTestId('taste-status')).toHaveTextContent('Updating…');
    expect(screen.queryByTestId('top-genres')).toBeNull();
    expect(screen.getByText(/Rate movies to sharpen it/)).toBeOnTheScreen();
  });

  // Bug 9: the server now sends only genres above neutral, so there may be none or fewer than five.
  it('says so kindly when no genre is above neutral yet', async () => {
    await render(<PersonaCard profile={makeTasteProfile({ topGenres: [] })} />);
    expect(screen.queryByTestId('top-genres')).toBeNull();
    expect(screen.getByTestId('top-genres-note')).toHaveTextContent(/No standout genres yet/);
  });

  it('lists a short top-genres list as is, with a note', async () => {
    await render(<PersonaCard profile={makeTasteProfile({ topGenres: [makeDimension()] })} />);
    expect(screen.getByTestId('top-genres')).toHaveTextContent(/Science Fiction7\.4/);
    expect(screen.getByTestId('top-genres-note')).toHaveTextContent(/above neutral/);
  });

  it('has no note with a full five', async () => {
    const five = ['28', '18', '35', '27', '99'].map((id) => makeDimension({ key: `genre:${id}`, label: `G${id}`, learned: 8 }));
    await render(<PersonaCard profile={makeTasteProfile({ topGenres: five })} />);
    expect(screen.queryByTestId('top-genres-note')).toBeNull();
  });
});

describe('TasteSection (Profile)', () => {
  it('loads the persona card and links to the editor and history', async () => {
    serveRoutes(fetchMock, [{ path: '/me/taste-profile', body: makeTasteProfile() }]);
    await renderWithClient(<TasteSection />);
    await waitFor(() => expect(screen.getByTestId('persona-card')).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Edit taste' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/taste');
    await fireEvent.press(screen.getByRole('button', { name: 'How it changed' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/taste/history');
  });

  it('shows a skeleton while loading', async () => {
    serveRoutes(fetchMock, [{ path: '/me/taste-profile', hang: true }]);
    await renderWithClient(<TasteSection />);
    expect(screen.getByTestId('taste-loading', { includeHiddenElements: true })).toBeOnTheScreen();
  });

  it('shows an error with retry', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/me/taste-profile', status: 500, body: { message: 'x' }, times: 1 },
      { path: '/me/taste-profile', body: makeTasteProfile() },
    ]);
    await renderWithClient(<TasteSection />);
    await waitFor(() => expect(screen.getByText("Couldn't load your taste")).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByTestId('persona-card')).toBeOnTheScreen());
    expect(api.callsTo('GET', '/me/taste-profile')).toHaveLength(2);
  });
});

describe('Taste editor', () => {
  const profile = makeTasteProfile();
  const open = async (extra: Parameters<typeof serveRoutes>[1] = []) => {
    const api = serveRoutes(fetchMock, [...extra, { path: '/me/taste-profile', body: profile }]);
    await renderWithClient(<TasteEditorScreen />);
    await waitFor(() => expect(screen.getByTestId('taste-row-genre:878')).toBeOnTheScreen());
    return api;
  };

  it('shows each genre and vibe with its stated slider and learned value', async () => {
    await open();
    expect(screen.getByRole('header', { name: 'Genres' })).toBeOnTheScreen();
    expect(screen.getByRole('header', { name: 'Vibes' })).toBeOnTheScreen();
    expect(screen.getByLabelText('Horror').props.accessibilityValue.now).toBe(4);
    expect(screen.getByTestId('stated-vs-learned-genre:27')).toHaveTextContent('You said 4 · your ratings say 7');
    expect(screen.getByTestId('stated-vs-learned-genre:878')).toHaveTextContent('You said 8 · your ratings say 7.4');
    expect(screen.getByTestId('learned-genre:27')).toHaveProp('accessibilityLabel', 'Your ratings say 7 out of 10');
    // Unset and unlearned: slider at 5, no marker.
    expect(screen.getByLabelText('Comedy').props.accessibilityValue.now).toBe(5);
    expect(screen.getByTestId('stated-vs-learned-genre:35')).toHaveTextContent('Not set · not enough ratings yet');
    expect(screen.queryByTestId('learned-genre:35')).toBeNull();
    expect(screen.getByTestId('stated-vs-learned-mood:dark-gritty')).toHaveTextContent('Not set · your ratings say 3.2');
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  });

  it('saves stated genres only when a genre moved (replace-all, keeping the others)', async () => {
    const api = await open([{ method: 'PUT', path: '/me/preferences/genres', body: [] }]);
    await slideTo('Horror', 9);
    expect(screen.getByTestId('stated-vs-learned-genre:27')).toHaveTextContent('You said 9 · your ratings say 7');
    await slideTo('Comedy', 3);
    await fireEvent.press(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(screen.getByText('Saved. Your taste is updating.')).toBeOnTheScreen());
    expect(api.callsTo('PUT', '/me/preferences/genres')[0]?.body).toEqual([
      { genreId: 878, score: 8 },
      { genreId: 27, score: 9 },
      { genreId: 35, score: 3 },
    ]);
    expect(api.callsTo('PUT', '/me/preferences/moods')).toHaveLength(0);
    expect(mockShowTasteUpdating).toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  });

  it('saves moods too when a vibe moved', async () => {
    const api = await open([
      { method: 'PUT', path: '/me/preferences/genres', body: [] },
      { method: 'PUT', path: '/me/preferences/moods', body: [] },
    ]);
    await slideTo('Dark & gritty', 2);
    await fireEvent.press(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.callsTo('PUT', '/me/preferences/moods')).toHaveLength(1));
    expect(api.callsTo('PUT', '/me/preferences/moods')[0]?.body).toEqual([
      { moodTag: 'cozy', score: 6 },
      { moodTag: 'dark-gritty', score: 2 },
    ]);
    expect(api.callsTo('PUT', '/me/preferences/genres')).toHaveLength(0);
  });

  it('shows a server validation error and keeps the changes', async () => {
    await open([{ method: 'PUT', path: '/me/preferences/genres', status: 400, body: { message: 'Unknown genre 878' } }]);
    await slideTo('Horror', 9);
    await fireEvent.press(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(screen.getByText('Unknown genre 878')).toBeOnTheScreen());
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();
    expect(mockShowTasteUpdating).not.toHaveBeenCalled();
  });

  it('links to recalibrate and history', async () => {
    await open();
    await fireEvent.press(screen.getByRole('button', { name: 'Recalibrate' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/taste/recalibrate');
    await fireEvent.press(screen.getByRole('button', { name: 'How your taste changed' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/taste/history');
  });

  it('shows skeletons while loading', async () => {
    serveRoutes(fetchMock, [{ path: '/me/taste-profile', hang: true }]);
    await renderWithClient(<TasteEditorScreen />);
    expect(screen.getByTestId('taste-editor-loading')).toBeOnTheScreen();
  });

  it('shows a retryable error', async () => {
    serveRoutes(fetchMock, [{ path: '/me/taste-profile', status: 500, body: { message: 'x' } }]);
    await renderWithClient(<TasteEditorScreen />);
    await waitFor(() => expect(screen.getByText("Couldn't load your taste")).toBeOnTheScreen());
    expect(screen.getByRole('button', { name: 'Try again' })).toBeOnTheScreen();
  });
});

describe('Taste history', () => {
  it('lists versions newest first with reason, time and top genres', async () => {
    serveRoutes(fetchMock, [
      {
        path: '/me/taste/history',
        body: [
          makeHistoryEntry({ version: 3, reason: 'RATING', createdAt: new Date(Date.now() - 2 * 3600_000).toISOString() }),
          makeHistoryEntry({ version: 2, reason: 'TASTE_EDIT', topGenres: [] }),
          makeHistoryEntry({ version: 1, reason: 'ONBOARDING' }),
        ],
      },
    ]);
    await renderWithClient(<TasteHistoryScreen />);
    await waitFor(() => expect(screen.getByTestId('history-list')).toBeOnTheScreen());
    expect(screen.getByTestId('history-3')).toHaveTextContent(/Rated movies/);
    expect(screen.getByTestId('history-3')).toHaveTextContent(/v3/);
    expect(screen.getByTestId('history-3')).toHaveTextContent(/2 hours ago/);
    expect(screen.getByTestId('history-3')).toHaveTextContent(/Science Fiction 7\.4/);
    expect(screen.getByTestId('history-2')).toHaveTextContent(/Edited your taste/);
    expect(screen.getByTestId('history-2-no-genres')).toHaveTextContent(/No standout genres/);
    expect(screen.queryByTestId('history-3-no-genres')).toBeNull();
    expect(screen.getByTestId('history-1')).toHaveTextContent(/Finished onboarding/);
  });

  it('shows skeletons while loading', async () => {
    serveRoutes(fetchMock, [{ path: '/me/taste/history', hang: true }]);
    await renderWithClient(<TasteHistoryScreen />);
    expect(screen.getByTestId('history-loading')).toBeOnTheScreen();
  });

  it('has an empty state', async () => {
    serveRoutes(fetchMock, [{ path: '/me/taste/history', body: [] }]);
    await renderWithClient(<TasteHistoryScreen />);
    await waitFor(() => expect(screen.getByText('No changes yet')).toBeOnTheScreen());
  });

  it('shows a retryable error', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/me/taste/history', status: 500, body: { message: 'x' }, times: 1 },
      { path: '/me/taste/history', body: [] },
    ]);
    await renderWithClient(<TasteHistoryScreen />);
    await waitFor(() => expect(screen.getByText("Couldn't load your taste history")).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByText('No changes yet')).toBeOnTheScreen());
    expect(api.callsTo('GET', '/me/taste/history')).toHaveLength(2);
  });
});

describe('Recalibrate', () => {
  it('reuses the deck with Done always available, then shows the toast and goes back', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/me/calibration/next?count=10', body: [makeMovie({ id: 1, title: 'Alien' }), makeMovie({ id: 2, title: 'Up' })] },
      { method: 'POST', path: '/me/ratings/batch', status: 204 },
    ]);
    await renderWithClient(<RecalibrateScreen />);
    await waitFor(() => expect(screen.getByTestId('calibration-card-1')).toBeOnTheScreen());
    expect(screen.getByRole('button', { name: 'Done' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Rate 5' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(routerState.router.back).toHaveBeenCalled());
    expect(api.callsTo('POST', '/me/ratings/batch')[0]?.body).toEqual([{ movieId: 1, score: 5, unseen: false }]);
    expect(mockShowTasteUpdating).toHaveBeenCalled();
  });

  it('leaves without a toast when nothing was answered (and replaces after a deep link)', async () => {
    serveRoutes(fetchMock, [{ path: '/me/calibration/next?count=10', body: [makeMovie({ id: 1 })] }]);
    routerState.router.canGoBack.mockReturnValue(false);
    await renderWithClient(<RecalibrateScreen />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Done' })).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith('/taste'));
    expect(mockShowTasteUpdating).not.toHaveBeenCalled();
  });
});
