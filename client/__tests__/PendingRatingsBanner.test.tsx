jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { fireEvent, screen, waitFor, within } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { PENDING_PREVIEW, PendingRatingsBanner, watchedWith } from '../components/rating/PendingRatingsBanner';
import { QuickRateProvider } from '../components/rating/QuickRateProvider';
import { serveRoutes, type Route } from '../test-utils/fetchRoutes';
import { makeMovie, makePendingRating, makeRating, makeTasteProfile } from '../test-utils/fixtures';
import { renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

const heat = makePendingRating({
  movie: makeMovie({ id: 100, title: 'Heat', posterUrl: 'https://img/heat.jpg' }),
  groupName: 'Film club',
});
const up = makePendingRating({ movie: makeMovie({ id: 200, title: 'Up', posterUrl: null }), groupName: null });

const renderBanner = () =>
  renderWithClient(
    <QuickRateProvider>
      <PendingRatingsBanner />
    </QuickRateProvider>,
  );

describe('PendingRatingsBanner — visibility', () => {
  it('renders nothing while loading', async () => {
    serveRoutes(fetchMock, [{ path: '/me/pending-ratings', hang: true }]);
    await renderBanner();
    expect(screen.queryByTestId('pending-ratings')).toBeNull();
  });

  it('renders nothing when the list is empty', async () => {
    const api = serveRoutes(fetchMock, [{ path: '/me/pending-ratings', body: [] }]);
    await renderBanner();
    await waitFor(() => expect(api.callsTo('GET', '/me/pending-ratings')).toHaveLength(1));
    expect(screen.queryByTestId('pending-ratings')).toBeNull();
  });

  it('renders nothing when the request fails (a nudge, never a blocker)', async () => {
    const api = serveRoutes(fetchMock, [{ path: '/me/pending-ratings', status: 500, body: { message: 'boom' } }]);
    await renderBanner();
    await waitFor(() => expect(api.callsTo('GET', '/me/pending-ratings')).toHaveLength(1));
    expect(screen.queryByTestId('pending-ratings')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('PendingRatingsBanner — list', () => {
  it('lists each movie with its poster and who it was watched with', async () => {
    serveRoutes(fetchMock, [{ path: '/me/pending-ratings', body: [heat, up] }]);
    await renderBanner();
    const banner = await screen.findByTestId('pending-ratings');
    expect(within(banner).getByRole('header', { name: 'Rate what you watched' })).toBeOnTheScreen();
    const heatRow = within(banner).getByTestId('pending-rating-100');
    expect(within(heatRow).getByText('Heat')).toBeOnTheScreen();
    expect(within(heatRow).getByText('Watched with Film club')).toBeOnTheScreen();
    expect(within(heatRow).getByTestId('pending-poster-100')).toBeOnTheScreen();
    const upRow = within(banner).getByTestId('pending-rating-200');
    expect(within(upRow).getByText('Watched on a movie night')).toBeOnTheScreen();
    expect(within(upRow).getByTestId('icon-film-outline')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Rate Heat' })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Dismiss Up' }).props.accessibilityHint).toBe(
      "You won't be asked to rate it again",
    );
  });

  it(`shows ${PENDING_PREVIEW} at first, then the rest on "Show N more"`, async () => {
    const many = Array.from({ length: 5 }, (_, i) =>
      makePendingRating({ movie: makeMovie({ id: i + 1, title: `Movie ${i + 1}` }) }),
    );
    serveRoutes(fetchMock, [{ path: '/me/pending-ratings', body: many }]);
    await renderBanner();
    await screen.findByTestId('pending-ratings');
    expect(screen.getByText('Movie 3')).toBeOnTheScreen();
    expect(screen.queryByText('Movie 4')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Show 2 more' }));
    expect(screen.getByText('Movie 5')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: /^Show \d more/ })).toBeNull();
  });

  it('watchedWith copy', () => {
    expect(watchedWith({ groupName: 'Cousins' })).toBe('Watched with Cousins');
    expect(watchedWith({ groupName: null })).toBe('Watched on a movie night');
  });
});

describe('PendingRatingsBanner — dismiss', () => {
  it('dismisses at once and POSTs the dismissal', async () => {
    const api = serveRoutes(fetchMock, [
      { method: 'POST', path: '/me/pending-ratings/100/dismiss', status: 204 },
      { path: '/me/pending-ratings', body: [heat, up] },
    ]);
    await renderBanner();
    await screen.findByTestId('pending-rating-100');
    await fireEvent.press(screen.getByRole('button', { name: 'Dismiss Heat' }));
    expect(screen.queryByTestId('pending-rating-100')).toBeNull();
    expect(screen.getByTestId('pending-rating-200')).toBeOnTheScreen();
    await waitFor(() => expect(api.callsTo('POST', '/me/pending-ratings/100/dismiss')).toHaveLength(1));
  });

  it('dismissing the last one hides the banner', async () => {
    serveRoutes(fetchMock, [
      { method: 'POST', path: '/me/pending-ratings/100/dismiss', status: 204 },
      { path: '/me/pending-ratings', body: [heat] },
    ]);
    await renderBanner();
    await screen.findByTestId('pending-ratings');
    await fireEvent.press(screen.getByRole('button', { name: 'Dismiss Heat' }));
    expect(screen.queryByTestId('pending-ratings')).toBeNull();
  });

  it('brings the movie back and says why when the dismissal fails', async () => {
    serveRoutes(fetchMock, [
      { method: 'POST', path: '/me/pending-ratings/100/dismiss', status: 500, body: { message: 'boom' } },
      { path: '/me/pending-ratings', body: [heat, up] },
    ]);
    await renderBanner();
    await screen.findByTestId('pending-rating-100');
    await fireEvent.press(screen.getByRole('button', { name: 'Dismiss Heat' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/The server had a problem/);
    expect(screen.getByTestId('pending-rating-100')).toBeOnTheScreen();
  });
});

describe('PendingRatingsBanner — rate', () => {
  it('opens quick rate on the movie and saves it as POST_WATCH, then drops it from the list', async () => {
    const routes: Route[] = [
      { path: '/me/ratings/100', status: 404, body: { message: 'Not rated' } },
      {
        method: 'PUT',
        path: '/ratings/100',
        body: (b: unknown) => makeRating({ movie: heat.movie, ...(b as object) }),
      },
      { path: '/me/taste-profile', body: makeTasteProfile({ status: 'REBUILDING' }) },
      { path: '/me/pending-ratings', body: [heat, up], times: 1 },
      { path: '/me/pending-ratings', body: [up] },
    ];
    const api = serveRoutes(fetchMock, routes);
    await renderBanner();
    await fireEvent.press(await screen.findByRole('button', { name: 'Rate Heat' }));
    await fireEvent.press(await screen.findByRole('radio', { name: /^8 out of 10/ }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save rating' }));
    await waitFor(() => expect(api.callsTo('PUT', '/ratings/100')).toHaveLength(1));
    expect(api.callsTo('PUT', '/ratings/100')[0]!.body).toMatchObject({ score: 8, source: 'POST_WATCH' });
    await waitFor(() => expect(screen.queryByTestId('pending-rating-100')).toBeNull());
    expect(screen.getByTestId('pending-rating-200')).toBeOnTheScreen();
  });
});
