jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import NewSessionScreen from '../app/sessions/new';
import { setupFromSession, useLastSessionSetup } from '../api/sessionSetup';
import {
  describeFilters,
  filtersFromDTO,
  parseMemberIdsParam,
  parseMovieIdParam,
} from '../components/sessions/SessionShortcuts';
import { serveRoutes, type Route } from '../test-utils/fetchRoutes';
import {
  makeFriend,
  makeGroup,
  makeMovie,
  makeProfile,
  makeSession,
  makeSessionMember,
  makeSessionSummary,
} from '../test-utils/fixtures';
import { renderHookWithClient, renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';

notifyManager.setScheduler((cb) => cb());

const GRACE = '11111111-1111-4111-8111-111111111111';
const ALAN = '22222222-2222-4222-8222-222222222222';
const EX = '33333333-3333-4333-8333-333333333333';
const ME = 'u1';

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

const friends = [
  makeFriend({ id: GRACE, displayName: 'Grace Hopper', username: 'grace' }),
  makeFriend({ id: ALAN, displayName: 'Alan Turing', username: 'alan' }),
];
const lastTonight = makeSession({
  id: 's-last',
  groupId: null,
  groupName: null,
  strategy: 'LEAST_MISERY',
  filters: { maxRuntime: 120, minYear: 1990, genreIds: [35] },
  members: [
    makeSessionMember({ id: ME, displayName: 'Ada Lovelace', username: 'ada', friendship: 'NONE' }),
    makeSessionMember({ id: GRACE, displayName: 'Grace Hopper', username: 'grace', friendship: 'FRIENDS' }),
    makeSessionMember({ id: EX, displayName: 'Former Friend', username: 'ex', friendship: 'NONE' }),
  ],
});

const base: Route[] = [
  { path: '/me', body: makeProfile({ id: ME }) },
  { path: '/friends', body: { items: friends, nextCursor: null } },
  { path: '/genres', body: [{ id: 35, name: 'Comedy' }] },
];
const withHistory = (...routes: Route[]): Route[] => [
  ...routes,
  ...base,
  {
    path: '/sessions',
    body: {
      items: [makeSessionSummary({ id: 's-group', groupId: 'g1' }), makeSessionSummary({ id: 's-last', groupId: null })],
      nextCursor: null,
    },
  },
  { path: '/sessions/s-last', body: lastTonight },
];

const findMovies = () => screen.getByRole('button', { name: 'Find movies' });

describe('route params', () => {
  it('parses member ids: UUIDs only, deduplicated, capped', () => {
    expect(parseMemberIdsParam(`${GRACE},${ALAN},${GRACE}`, 19)).toEqual([GRACE, ALAN]);
    expect(parseMemberIdsParam([GRACE, `${ALAN}, junk`], 19)).toEqual([GRACE, ALAN]);
    expect(parseMemberIdsParam(`${GRACE},${ALAN}`, 1)).toEqual([GRACE]);
    expect(parseMemberIdsParam('../../x,u1', 19)).toEqual([]);
    expect(parseMemberIdsParam(undefined, 19)).toEqual([]);
  });

  it.each([
    ['438631', 438631],
    [['12', '13'], 12],
    ['0', null],
    ['-4', null],
    ['1e3', null],
    ['abc', null],
    [undefined, null],
  ])('parseMovieIdParam(%p) → %p', (value, id) => expect(parseMovieIdParam(value)).toBe(id));

  it('"Movie night with…" preselects the friend and skips "Same crew again"', async () => {
    routerState.params = { memberIds: ALAN };
    const api = serveRoutes(fetchMock, [
      ...base,
      { method: 'POST', path: '/sessions', body: makeSession({ id: 's9' }) },
    ]);
    await renderWithClient(<NewSessionScreen />);
    await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Alan Turing' })).toBeChecked());
    expect(screen.getByRole('checkbox', { name: 'Grace Hopper' })).not.toBeChecked();
    expect(screen.queryByTestId('same-crew')).toBeNull();
    expect(api.callsTo('GET', '/sessions')).toHaveLength(0);
    await fireEvent.press(findMovies());
    await waitFor(() => expect(api.callsTo('POST', '/sessions')).toHaveLength(1));
    expect(api.callsTo('POST', '/sessions')[0]?.body).toEqual({ memberIds: [ALAN], filters: null, strategy: 'MEAN' });
    expect(routerState.router.replace).toHaveBeenCalledWith('/sessions/s9');
  });

  it('"Watch with…" shows the movie as context (the session is not seeded with it)', async () => {
    routerState.params = { movieId: '100' };
    const api = serveRoutes(fetchMock, [
      ...withHistory({
        path: '/movies/100',
        body: { ...makeMovie({ id: 100, title: 'Heat' }), runtime: 170, overview: '', backdropUrl: null, genres: [], voteAverage: null, popularity: 1 },
      }),
      { method: 'POST', path: '/sessions', body: makeSession({ id: 's9' }) },
    ]);
    await renderWithClient(<NewSessionScreen />);
    expect(await screen.findByText('Thinking of Heat?')).toBeOnTheScreen();
    await fireEvent.press(await screen.findByRole('checkbox', { name: 'Grace Hopper' }));
    await fireEvent.press(findMovies());
    await waitFor(() => expect(api.callsTo('POST', '/sessions')).toHaveLength(1));
    expect(api.callsTo('POST', '/sessions')[0]?.body).not.toHaveProperty('movieId');
  });
});

describe('Same crew again', () => {
  it('fills in the last Tonight crew (friends only, not you), filters and strategy from the server', async () => {
    const api = serveRoutes(fetchMock, [
      ...withHistory(),
      { method: 'POST', path: '/sessions', body: makeSession({ id: 's9' }) },
    ]);
    await renderWithClient(<NewSessionScreen />);
    const card = await screen.findByRole('button', { name: /^Same crew again: Grace Hopper · Under 2h · from 1990 · 1 genre · Least misery$/ });
    // The newest ad-hoc session is used, not the group one.
    expect(api.callsTo('GET', '/sessions/s-group')).toHaveLength(0);
    await fireEvent.press(card);
    expect(screen.queryByTestId('same-crew')).toBeNull();
    expect(screen.getByTestId('same-crew-used')).toBeOnTheScreen();
    expect(screen.getByRole('checkbox', { name: 'Grace Hopper' })).toBeChecked();
    expect(screen.getByLabelText('Released from (year)').props.value).toBe('1990');
    expect(screen.getByRole('button', { name: 'Advanced, Least misery' })).toBeOnTheScreen();
    await fireEvent.press(findMovies());
    await waitFor(() => expect(api.callsTo('POST', '/sessions')).toHaveLength(1));
    expect(api.callsTo('POST', '/sessions')[0]?.body).toEqual({
      memberIds: [GRACE],
      filters: { maxRuntime: 120, minYear: 1990, genreIds: [35] },
      strategy: 'LEAST_MISERY',
    });
  });

  it('not offered without a past Tonight session', async () => {
    const api = serveRoutes(fetchMock, [
      ...base,
      { path: '/sessions', body: { items: [makeSessionSummary({ groupId: 'g1' })], nextCursor: null } },
    ]);
    await renderWithClient(<NewSessionScreen />);
    await waitFor(() => expect(api.callsTo('GET', '/sessions')).toHaveLength(1));
    await screen.findByRole('checkbox', { name: 'Grace Hopper' });
    expect(screen.queryByTestId('same-crew')).toBeNull();
  });

  it('for a group: "Same as last time" copies its last session’s options', async () => {
    routerState.params = { groupId: 'g1' };
    const api = serveRoutes(fetchMock, [
      ...base,
      { path: '/groups/g1', body: makeGroup() },
      { path: '/sessions?groupId=g1', body: { items: [makeSessionSummary({ id: 's-g', groupId: 'g1' })], nextCursor: null } },
      { path: '/sessions/s-g', body: makeSession({ id: 's-g', strategy: 'FAIRNESS', filters: { maxRuntime: 90 } }) },
      { method: 'POST', path: '/sessions', body: makeSession({ id: 's9' }) },
    ]);
    await renderWithClient(<NewSessionScreen />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Same crew again: Under 1h 30 · Fairness' }));
    await fireEvent.press(findMovies());
    await waitFor(() => expect(api.callsTo('POST', '/sessions')).toHaveLength(1));
    expect(api.callsTo('POST', '/sessions')[0]?.body).toEqual({
      groupId: 'g1',
      filters: { maxRuntime: 90, minYear: null, genreIds: null },
      strategy: 'FAIRNESS',
    });
  });
});

describe('Save these people as a group', () => {
  it('appears once friends are picked and saves them via POST /groups; the session stays ad hoc', async () => {
    const api = serveRoutes(fetchMock, [
      ...base,
      { path: '/sessions', body: { items: [], nextCursor: null } },
      {
        method: 'POST',
        path: '/groups',
        body: (b: unknown) => makeGroup({ id: 'g9', name: (b as { name: string }).name, emoji: (b as { emoji: string }).emoji }),
      },
      { method: 'POST', path: '/sessions', body: makeSession({ id: 's9' }) },
    ]);
    await renderWithClient(<NewSessionScreen />);
    await screen.findByRole('checkbox', { name: 'Grace Hopper' });
    expect(screen.queryByRole('button', { name: 'Save these people as a group' })).toBeNull();
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Grace Hopper' }));
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Alan Turing' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save these people as a group' }));
    expect(screen.getByText('2 friends will get an invite to join.')).toBeOnTheScreen();

    // A name is required.
    await fireEvent.press(screen.getByRole('button', { name: 'Save group' }));
    expect(screen.getByText('Give your group a name.')).toBeOnTheScreen();
    expect(api.callsTo('POST', '/groups')).toHaveLength(0);

    await fireEvent.changeText(screen.getByLabelText('Group name'), '  Roommates ');
    await fireEvent.press(screen.getByRole('radio', { name: 'Emoji 🍿' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save group' }));
    await waitFor(() => expect(screen.getByTestId('save-crew-done')).toHaveTextContent(/Saved as 🍿 Roommates/));
    expect(api.callsTo('POST', '/groups')[0]?.body).toEqual({
      name: 'Roommates',
      emoji: '🍿',
      color: expect.any(String),
      memberIds: [GRACE, ALAN],
    });

    await fireEvent.press(findMovies());
    await waitFor(() => expect(api.callsTo('POST', '/sessions')).toHaveLength(1));
    expect(api.callsTo('POST', '/sessions')[0]?.body).toMatchObject({ memberIds: [GRACE, ALAN] });
  });

  it('shows a save failure and can be cancelled', async () => {
    serveRoutes(fetchMock, [
      ...base,
      { path: '/sessions', body: { items: [], nextCursor: null } },
      { method: 'POST', path: '/groups', status: 429, body: { message: 'Too many requests' } },
    ]);
    await renderWithClient(<NewSessionScreen />);
    await fireEvent.press(await screen.findByRole('checkbox', { name: 'Grace Hopper' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save these people as a group' }));
    await fireEvent.changeText(screen.getByLabelText('Group name'), 'Crew');
    await fireEvent.press(screen.getByRole('button', { name: 'Save group' }));
    expect(await screen.findByText('Too many requests')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByTestId('save-crew-form')).toBeNull();
    expect(screen.getByRole('button', { name: 'Save these people as a group' })).toBeOnTheScreen();
  });

  it('is not offered for a saved group', async () => {
    routerState.params = { groupId: 'g1' };
    serveRoutes(fetchMock, [
      ...base,
      { path: '/groups/g1', body: makeGroup() },
      { path: '/sessions?groupId=g1', body: { items: [], nextCursor: null } },
    ]);
    await renderWithClient(<NewSessionScreen />);
    await screen.findByText('2 members');
    expect(screen.queryByRole('button', { name: 'Save these people as a group' })).toBeNull();
  });
});

describe('Advanced', () => {
  it('starts closed and toggles the strategy picker', async () => {
    serveRoutes(fetchMock, [...base, { path: '/sessions', body: { items: [], nextCursor: null } }]);
    await renderWithClient(<NewSessionScreen />);
    const toggle = screen.getByRole('button', { name: 'Advanced, Mean' });
    expect(toggle).toHaveProp('accessibilityState', { expanded: false });
    expect(screen.queryByRole('radio', { name: /^Mean:/ })).toBeNull();
    await fireEvent.press(toggle);
    expect(screen.getByRole('radio', { name: /^Mean:/ })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Advanced, Mean' }));
    expect(screen.queryByRole('radio', { name: /^Mean:/ })).toBeNull();
  });
});

describe('sessionSetup helpers', () => {
  it('setupFromSession keeps friends only and leaves you out', () => {
    const setup = setupFromSession(lastTonight, ME);
    expect(setup.members.map((m) => m.id)).toEqual([GRACE]);
    expect(setup.strategy).toBe('LEAST_MISERY');
    expect(setup.sessionId).toBe('s-last');
  });

  it('describeFilters / filtersFromDTO', () => {
    expect(describeFilters({})).toBe('');
    expect(describeFilters({ maxRuntime: 100, genreIds: [1, 2] })).toBe('Under 100 min · 2 genres');
    expect(filtersFromDTO({})).toEqual({ maxRuntime: null, fromYear: '', genreIds: [] });
    expect(filtersFromDTO({ maxRuntime: 90, minYear: 2001, genreIds: [35] })).toEqual({
      maxRuntime: 90,
      fromYear: '2001',
      genreIds: [35],
    });
  });

  it('useLastSessionSetup: null without history; waits for /me', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/me', body: makeProfile({ id: ME }) },
      { path: '/sessions', body: { items: [], nextCursor: null } },
    ]);
    const { result } = await renderHookWithClient(() => useLastSessionSetup(null));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
    expect(api.callsTo('GET', '/me').length).toBeGreaterThan(0);
  });

  it('useLastSessionSetup: disabled asks nothing about sessions', async () => {
    const api = serveRoutes(fetchMock, [{ path: '/me', body: makeProfile({ id: ME }) }]);
    await renderHookWithClient(() => useLastSessionSetup(null, { enabled: false }));
    await waitFor(() => expect(api.callsTo('GET', '/me').length).toBeGreaterThan(0));
    expect(api.callsTo('GET', '/sessions')).toHaveLength(0);
  });
});
