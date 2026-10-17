jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import MovieRatingsScreen from '../app/movie/[id]/ratings';
import UserRatingsScreen from '../app/user/[id]/ratings';
import { QuickRateProvider } from '../components/rating/QuickRateProvider';
import { serveRoutes, type Route } from '../test-utils/fetchRoutes';
import {
  makeFeedItem,
  makeMovie,
  makeProfile,
  makePublicProfile,
  makeRating,
  makeTasteProfile,
  makeUser,
} from '../test-utils/fixtures';
import { renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

const dune = {
  ...makeMovie({ id: 438631, title: 'Dune' }),
  runtime: 155,
  overview: null,
  backdropUrl: null,
  genres: [],
  popularity: null,
};
const page = (items: unknown[], nextCursor: string | null = null) => ({ items, nextCursor });
const friendItem = makeFeedItem({ id: 'f1', author: makeUser({ id: 'a1', displayName: 'Rita Moreno' }), likeCount: 2 });
const publicItem = makeFeedItem({
  id: 'p1',
  author: makeUser({ id: 'a2', displayName: 'Omar Sy' }),
  visibility: 'PUBLIC',
});

const renderScreen = (ui: React.ReactElement) => renderWithClient(<QuickRateProvider>{ui}</QuickRateProvider>);

describe('MovieRatingsScreen (/movie/[id]/ratings)', () => {
  const routes = (): Route[] => [
    { path: '/movies/438631', body: dune },
    { path: '/movies/438631/ratings?scope=FRIENDS', body: page([friendItem], 'c2'), times: 1 },
    { path: '/movies/438631/ratings?scope=FRIENDS&cursor=c2', body: page([makeFeedItem({ id: 'f2' })]) },
    { path: '/movies/438631/ratings?scope=FRIENDS', body: page([friendItem], 'c2') },
    { path: '/movies/438631/ratings?scope=EVERYONE', body: page([publicItem]) },
  ];

  it('lists FRIENDS ratings first, pages at the end, and switches to EVERYONE', async () => {
    routerState.params = { id: '438631' };
    const api = serveRoutes(fetchMock, routes());
    await renderScreen(<MovieRatingsScreen />);
    expect(await screen.findByTestId('feed-card-f1')).toBeOnTheScreen();
    expect(screen.getByRole('header', { name: 'Ratings' })).toBeOnTheScreen();
    expect(await screen.findByText('Dune')).toBeOnTheScreen();
    expect(screen.getByRole('radio', { name: 'Friends' })).toBeChecked();
    // The movie is the page: no poster row on the cards.
    expect(screen.queryByRole('button', { name: /^Heat/ })).toBeNull();

    await act(async () => {
      fireEvent(screen.getByTestId('movie-ratings-all'), 'endReached');
    });
    expect(await screen.findByTestId('feed-card-f2')).toBeOnTheScreen();
    expect(api.callsTo('GET', '/movies/438631/ratings?scope=FRIENDS&cursor=c2')).toHaveLength(1);

    await fireEvent.press(screen.getByRole('radio', { name: 'Everyone' }));
    expect(await screen.findByTestId('feed-card-p1')).toBeOnTheScreen();
    expect(screen.queryByTestId('feed-card-f1')).toBeNull();
    expect(api.callsTo('GET', '/movies/438631/ratings?scope=EVERYONE')).toHaveLength(1);
  });

  it('opens on the scope from the link', async () => {
    routerState.params = { id: '438631', scope: 'EVERYONE' };
    serveRoutes(fetchMock, routes());
    await renderScreen(<MovieRatingsScreen />);
    expect(await screen.findByTestId('feed-card-p1')).toBeOnTheScreen();
    expect(screen.getByRole('radio', { name: 'Everyone' })).toBeChecked();
  });

  it('pull-to-refresh refetches', async () => {
    routerState.params = { id: '438631' };
    const api = serveRoutes(fetchMock, routes());
    await renderScreen(<MovieRatingsScreen />);
    await screen.findByTestId('feed-card-f1');
    await act(async () => {
      fireEvent(screen.getByTestId('movie-ratings-all'), 'refresh');
    });
    await waitFor(() =>
      expect(api.callsTo('GET', /^\/movies\/438631\/ratings\?scope=FRIENDS/).length).toBeGreaterThan(1),
    );
  });

  it('empty states per scope', async () => {
    routerState.params = { id: '438631' };
    serveRoutes(fetchMock, [
      { path: '/movies/438631', body: dune },
      { path: /^\/movies\/438631\/ratings/, body: page([]) },
    ]);
    await renderScreen(<MovieRatingsScreen />);
    expect(await screen.findByText('No ratings from friends yet')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('radio', { name: 'Everyone' }));
    expect(await screen.findByText('No public ratings yet')).toBeOnTheScreen();
  });

  it('an error offers retry', async () => {
    routerState.params = { id: '438631' };
    const api = serveRoutes(fetchMock, [
      { path: '/movies/438631', body: dune },
      { path: '/movies/438631/ratings?scope=FRIENDS', status: 500, body: { message: 'boom' }, times: 1 },
      { path: '/movies/438631/ratings?scope=FRIENDS', body: page([friendItem]) },
    ]);
    await renderScreen(<MovieRatingsScreen />);
    expect(await screen.findByText("Couldn't load ratings")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('feed-card-f1')).toBeOnTheScreen();
    expect(api.callsTo('GET', '/movies/438631/ratings?scope=FRIENDS')).toHaveLength(2);
  });

  it.each([
    ['an unknown movie (404)', { id: '438631' }],
    ['a bad id', { id: 'abc' }],
  ])('not found for %s', async (_label, params) => {
    routerState.params = params;
    serveRoutes(fetchMock, [
      { path: '/movies/438631', status: 404, body: { message: 'Not found' } },
      { path: /^\/movies\/438631\/ratings/, status: 404, body: { message: 'Not found' } },
    ]);
    await renderScreen(<MovieRatingsScreen />);
    expect(await screen.findByText('Movie not found')).toBeOnTheScreen();
  });

  it('back goes to the movie after a deep link', async () => {
    routerState.params = { id: '438631' };
    routerState.router.canGoBack.mockReturnValue(false);
    serveRoutes(fetchMock, routes());
    await renderScreen(<MovieRatingsScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/movie/438631');
  });

  it('likes optimistically and rolls back when the server refuses', async () => {
    routerState.params = { id: '438631' };
    const api = serveRoutes(fetchMock, [
      { method: 'POST', path: '/ratings/f1/like', status: 500, body: { message: 'boom' } },
      ...routes(),
    ]);
    await renderScreen(<MovieRatingsScreen />);
    await screen.findByTestId('feed-card-f1');
    await fireEvent.press(screen.getByRole('button', { name: 'Like rating, 2 likes' }));
    await waitFor(() => expect(api.callsTo('POST', '/ratings/f1/like')).toHaveLength(1));
    expect(await screen.findByRole('button', { name: 'Like rating, 2 likes' })).not.toBeSelected();
  });

  it('keeps an optimistic like while the request is in flight', async () => {
    routerState.params = { id: '438631' };
    serveRoutes(fetchMock, [{ method: 'POST', path: '/ratings/f1/like', hang: true }, ...routes()]);
    await renderScreen(<MovieRatingsScreen />);
    await screen.findByTestId('feed-card-f1');
    await fireEvent.press(screen.getByRole('button', { name: 'Like rating, 2 likes' }));
    expect(await screen.findByRole('button', { name: 'Unlike rating, 3 likes' })).toBeSelected();
  });
});

describe('UserRatingsScreen (/user/[id]/ratings)', () => {
  const grace = makePublicProfile({ id: 'u2', displayName: 'Grace Hopper', friendship: 'FRIENDS' });
  const theirs = makeFeedItem({
    id: 'g1',
    author: makeUser({ id: 'u2' }),
    movie: makeMovie({ id: 100, title: 'Heat' }),
  });

  it('lists their ratings with the movie row but no author header, and pages', async () => {
    routerState.params = { id: 'u2' };
    const api = serveRoutes(fetchMock, [
      { path: '/me', body: makeProfile({ id: 'u1' }) },
      { path: '/users/u2', body: grace },
      { path: '/users/u2/ratings', body: page([theirs], 'c2') },
      {
        path: '/users/u2/ratings?cursor=c2',
        body: page([makeFeedItem({ id: 'g2', movie: makeMovie({ id: 200, title: 'Up' }) })]),
      },
    ]);
    await renderScreen(<UserRatingsScreen />);
    expect(await screen.findByTestId('feed-card-g1')).toBeOnTheScreen();
    expect(screen.getByRole('header', { name: 'Ratings' })).toBeOnTheScreen();
    expect(await screen.findByText('Grace Hopper')).toBeOnTheScreen();
    const card = within(screen.getByTestId('feed-card-g1'));
    expect(card.queryByRole('button', { name: /^Grace Hopper/ })).toBeNull();
    await fireEvent.press(card.getByRole('button', { name: 'Heat, 1995' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/100');

    await act(async () => {
      fireEvent(screen.getByTestId('user-ratings-all'), 'endReached');
    });
    expect(await screen.findByTestId('feed-card-g2')).toBeOnTheScreen();
    expect(api.callsTo('GET', '/users/u2/ratings?cursor=c2')).toHaveLength(1);
  });

  it('empty state for someone else', async () => {
    routerState.params = { id: 'u2' };
    serveRoutes(fetchMock, [
      { path: '/me', body: makeProfile({ id: 'u1' }) },
      { path: '/users/u2', body: grace },
      { path: '/users/u2/ratings', body: page([]) },
    ]);
    await renderScreen(<UserRatingsScreen />);
    expect(await screen.findByText("Grace Hopper hasn't shared any ratings with you yet.")).toBeOnTheScreen();
  });

  it('not available when blocked or unknown (404)', async () => {
    routerState.params = { id: 'u9' };
    serveRoutes(fetchMock, [
      { path: '/me', body: makeProfile({ id: 'u1' }) },
      { path: '/users/u9', status: 404, body: { message: 'Not found' } },
      { path: '/users/u9/ratings', status: 404, body: { message: 'Not found' } },
    ]);
    await renderScreen(<UserRatingsScreen />);
    expect(await screen.findByText("These ratings aren't available")).toBeOnTheScreen();
  });

  it('an error offers retry', async () => {
    routerState.params = { id: 'u2' };
    serveRoutes(fetchMock, [
      { path: '/me', body: makeProfile({ id: 'u1' }) },
      { path: '/users/u2', body: grace },
      { path: '/users/u2/ratings', status: 500, body: { message: 'boom' }, times: 1 },
      { path: '/users/u2/ratings', body: page([theirs]) },
    ]);
    await renderScreen(<UserRatingsScreen />);
    expect(await screen.findByText("Couldn't load ratings")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('feed-card-g1')).toBeOnTheScreen();
  });

  it('your own list: "Your ratings", Edit opens the sheet and saving refetches the list', async () => {
    routerState.params = { id: 'u1' };
    const mine = makeFeedItem({
      id: 'r1',
      mine: true,
      visibility: 'PRIVATE',
      author: makeUser({ id: 'u1' }),
      movie: makeMovie({ id: 100, title: 'Heat' }),
      score: 6,
    });
    const api = serveRoutes(fetchMock, [
      { path: '/me', body: makeProfile({ id: 'u1' }) },
      { path: '/users/u1', body: makePublicProfile({ id: 'u1', displayName: 'Ada Lovelace' }) },
      { path: '/users/u1/ratings', body: page([mine]) },
      { path: '/me/ratings/100', body: makeRating({ id: 'r1', score: 6, visibility: 'PRIVATE' }) },
      { method: 'PUT', path: '/ratings/100', body: makeRating({ id: 'r1', score: 9, visibility: 'PRIVATE' }) },
      { path: '/me/taste-profile', body: makeTasteProfile({ status: 'FRESH' }) },
    ]);
    await renderScreen(<UserRatingsScreen />);
    expect(await screen.findByTestId('feed-card-r1')).toBeOnTheScreen();
    expect(await screen.findByRole('header', { name: 'Your ratings' })).toBeOnTheScreen();
    expect(screen.getByLabelText('Visible to: Only you')).toBeOnTheScreen();
    const before = api.callsTo('GET', '/users/u1/ratings').length;

    await fireEvent.press(screen.getByRole('button', { name: 'Edit your rating of Heat' }));
    await fireEvent.press(await screen.findByRole('radio', { name: /^9 out of 10/ }));
    await fireEvent.press(screen.getByRole('button', { name: /Save rating|Update rating/ }));
    await waitFor(() => expect(api.callsTo('PUT', '/ratings/100')).toHaveLength(1));
    await waitFor(() => expect(api.callsTo('GET', '/users/u1/ratings').length).toBeGreaterThan(before));
  });
});
