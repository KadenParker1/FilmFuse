jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/social', () => ({ useFriends: jest.fn() }));
jest.mock('../api/movies', () => ({ ...jest.requireActual('../api/movies'), useGenres: jest.fn() }));
jest.mock('../api/groups', () => ({ ...jest.requireActual('../api/groups'), useGroup: jest.fn() }));
jest.mock('../api/sessionLive', () => ({ useSessionLive: jest.fn() }));
jest.mock('../api/sessions', () => ({
  ...jest.requireActual('../api/sessions'),
  useSession: jest.fn(),
  useCreateSession: jest.fn(),
  useCastVote: jest.fn(),
  useClearVote: jest.fn(),
  useRefreshSession: jest.fn(),
  usePickMovie: jest.fn(),
}));

import { fireEvent, render, screen } from '@testing-library/react-native';
import SessionScreen from '../app/sessions/[id]';
import NewSessionScreen from '../app/sessions/new';
import { useGroup } from '../api/groups';
import { useGenres } from '../api/movies';
import { useSessionLive } from '../api/sessionLive';
import * as sessions from '../api/sessions';
import { useFriends } from '../api/social';
import { ApiError, NetworkError } from '../lib/apiClient';
import {
  makeEffectiveFilter,
  makeFriend,
  makeGroup,
  makeMovie,
  makeResult,
  makeSession,
  makeSessionMember,
} from '../test-utils/fixtures';
import { infiniteResult, mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const m = <K extends keyof typeof sessions>(k: K) => sessions[k] as unknown as jest.Mock;
const MUTATIONS = ['useCreateSession', 'useCastVote', 'useClearVote', 'useRefreshSession', 'usePickMovie'] as const;
let muts: Record<(typeof MUTATIONS)[number], ReturnType<typeof mutationResult>>;

beforeEach(() => {
  routerState.reset();
  muts = {} as typeof muts;
  for (const name of MUTATIONS) {
    muts[name] = mutationResult();
    m(name).mockImplementation(() => muts[name]);
  }
  (useFriends as jest.Mock).mockReturnValue(
    infiniteResult({
      items: [
        makeFriend({ id: 'f1', displayName: 'Grace Hopper' }),
        makeFriend({ id: 'f2', displayName: 'Alan Turing', username: 'alan' }),
      ],
    }),
  );
  (useGenres as jest.Mock).mockReturnValue(queryResult({ data: [{ id: 35, name: 'Comedy' }] }));
  (useGroup as jest.Mock).mockReturnValue(
    queryResult({ data: makeGroup({ effectiveFilter: makeEffectiveFilter({ maxCertification: 'PG', label: 'Rated PG or below' }) }) }),
  );
  (useSessionLive as jest.Mock).mockReturnValue({ mode: 'live' });
});

// ---- New session ------------------------------------------------------------------------------

describe('NewSessionScreen', () => {
  const findMovies = () => screen.getByRole('button', { name: 'Find movies' });

  it('Tonight: needs at least one friend', async () => {
    await render(<NewSessionScreen />);
    expect(screen.getByRole('header', { name: 'Tonight' })).toBeOnTheScreen();
    await fireEvent.press(findMovies());
    expect(screen.getByRole('alert')).toHaveTextContent('Pick at least one friend to watch with.');
    expect(muts.useCreateSession.mutate).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Grace Hopper' }));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('Tonight: creates an ad-hoc session with filters and strategy, then opens it', async () => {
    muts.useCreateSession.mutate.mockImplementation((_b: unknown, o: { onSuccess: (s: { id: string }) => void }) =>
      o.onSuccess({ id: 's7' }),
    );
    await render(<NewSessionScreen />);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Alan Turing' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Under 1h 30' }));
    await fireEvent.changeText(screen.getByLabelText('Released from (year)'), '1985');
    await fireEvent.press(screen.getByRole('button', { name: 'Comedy' }));
    await fireEvent.press(screen.getByRole('radio', { name: /^Fairness:/ }));
    await fireEvent.press(findMovies());
    expect(muts.useCreateSession.mutate).toHaveBeenCalledWith(
      {
        memberIds: ['f2'],
        filters: { maxRuntime: 90, minYear: 1985, genreIds: [35] },
        strategy: 'FAIRNESS',
      },
      expect.anything(),
    );
    expect(routerState.router.replace).toHaveBeenCalledWith('/sessions/s7');
  });

  it('validates the from-year', async () => {
    await render(<NewSessionScreen />);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Alan Turing' }));
    await fireEvent.changeText(screen.getByLabelText('Released from (year)'), '1850');
    await fireEvent.press(findMovies());
    expect(screen.getByText('Pick a year between 1900 and 2100.')).toBeOnTheScreen();
    expect(muts.useCreateSession.mutate).not.toHaveBeenCalled();
  });

  it('from a group: members preselected, filter banner, default MEAN, no filters', async () => {
    routerState.params = { groupId: 'g1' };
    await render(<NewSessionScreen />);
    expect(useGroup).toHaveBeenCalledWith('g1');
    expect(screen.getByRole('header', { name: 'Start a session' })).toBeOnTheScreen();
    expect(screen.getByText('🎬 Film club')).toBeOnTheScreen();
    expect(screen.getByText('2 members')).toBeOnTheScreen();
    expect(screen.getByText('Rated PG or below')).toBeOnTheScreen();
    expect(screen.queryByRole('checkbox', { name: 'Grace Hopper' })).toBeNull();
    await fireEvent.press(findMovies());
    expect(muts.useCreateSession.mutate).toHaveBeenCalledWith(
      { groupId: 'g1', filters: null, strategy: 'MEAN' },
      expect.anything(),
    );
  });

  it('from a group: loading and error', async () => {
    routerState.params = { groupId: 'g1' };
    (useGroup as jest.Mock).mockReturnValue(queryResult());
    const { rerender } = await render(<NewSessionScreen />);
    expect(screen.getByTestId('session-group-loading')).toBeOnTheScreen();
    const refetch = jest.fn();
    (useGroup as jest.Mock).mockReturnValue(queryResult({ isPending: false, isError: true, error: new NetworkError(), refetch }));
    await rerender(<NewSessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('shows create errors and a busy button', async () => {
    muts.useCreateSession.isError = true;
    muts.useCreateSession.error = new ApiError(400, 'Exactly one of groupId or memberIds');
    muts.useCreateSession.isPending = true;
    await render(<NewSessionScreen />);
    expect(screen.getByRole('alert')).toHaveTextContent('Exactly one of groupId or memberIds');
    expect(findMovies()).toBeBusy();
  });

  it('back goes to the group (or Groups) after a deep link', async () => {
    routerState.router.canGoBack.mockReturnValue(false);
    routerState.params = { groupId: 'g1' };
    await render(<NewSessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/groups/g1');
  });
});

// ---- Session ----------------------------------------------------------------------------------

describe('SessionScreen', () => {
  beforeEach(() => {
    routerState.params = { id: 's1' };
  });
  const show = (session = makeSession()) =>
    m('useSession').mockReturnValue(queryResult({ data: session }));

  it('OPEN: header, live indicator, filter banner, members and ranked results', async () => {
    show(
      makeSession({
        effectiveFilter: makeEffectiveFilter({ maxCertification: 'PG_13', label: 'PG-13 or below · mild violence at most' }),
        members: [
          makeSessionMember({ id: 'u1', displayName: 'Ada Lovelace' }, { voted: true }),
          makeSessionMember({}, { hasVector: false }),
        ],
      }),
    );
    await render(<SessionScreen />);
    expect(m('useSession')).toHaveBeenCalledWith('s1');
    expect(useSessionLive).toHaveBeenCalledWith('s1');
    expect(screen.getByRole('header', { name: 'Film club' })).toBeOnTheScreen();
    expect(screen.getByText('Live')).toBeOnTheScreen();
    expect(screen.getByTestId('session-meta')).toHaveTextContent('Voting open · Mean');
    expect(screen.getByText('PG-13 or below · mild violence at most')).toBeOnTheScreen();
    expect(screen.getByText('1 of 2 voted')).toBeOnTheScreen();
    expect(screen.getByTestId('no-vector-hint')).toHaveTextContent(/Grace Hopper has no taste profile yet/);
    expect(screen.getByText('1. Heat')).toBeOnTheScreen();
    expect(screen.getByText('2. Up')).toBeOnTheScreen();
    expect(screen.getAllByTestId('fuse-meter')).toHaveLength(2);
  });

  it('shows the polling fallback indicator', async () => {
    show();
    (useSessionLive as jest.Mock).mockReturnValue({ mode: 'polling' });
    await render(<SessionScreen />);
    expect(screen.getByText('Updating every few seconds')).toBeOnTheScreen();
  });

  it('ad-hoc sessions are titled Tonight', async () => {
    show(makeSession({ groupId: null, groupName: null }));
    await render(<SessionScreen />);
    expect(screen.getByRole('header', { name: 'Tonight' })).toBeOnTheScreen();
  });

  it('votes up/down and clears a vote', async () => {
    show(makeSession({ results: [makeResult({ myVote: 'UP', votes: { up: 1, down: 0, vetoed: false } })] }));
    await render(<SessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Thumbs down, 0' }));
    expect(muts.useCastVote.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 100, vote: 'DOWN' });
    await fireEvent.press(screen.getByRole('button', { name: 'Thumbs up, 1' }));
    expect(muts.useClearVote.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 100 });
  });

  it('veto asks for confirmation first', async () => {
    show();
    await render(<SessionScreen />);
    await fireEvent.press(screen.getAllByRole('button', { name: 'Veto' })[0]!);
    expect(muts.useCastVote.mutate).not.toHaveBeenCalled();
    expect(screen.getByText('Veto Heat?')).toBeOnTheScreen();
    expect(screen.getByText(/Nobody sees who vetoed it/)).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByText('Veto Heat?')).toBeNull();
    expect(muts.useCastVote.mutate).not.toHaveBeenCalled();
    await fireEvent.press(screen.getAllByRole('button', { name: 'Veto' })[0]!);
    const buttons = screen.getAllByRole('button', { name: 'Veto' });
    await fireEvent.press(buttons[buttons.length - 1]!);
    expect(muts.useCastVote.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 100, vote: 'VETO' });
    expect(screen.queryByText('Veto Heat?')).toBeNull();
  });

  it('vetoed results sit greyed at the bottom without Pick', async () => {
    show(
      makeSession({
        results: [
          makeResult({ rank: 1, votes: { up: 0, down: 0, vetoed: true } }),
          makeResult({ rank: 2, movie: makeMovie({ id: 200, title: 'Up' }) }),
        ],
      }),
    );
    await render(<SessionScreen />);
    const ids = screen.getAllByTestId(/^result-/).map((e) => e.props.testID);
    expect(ids).toEqual(['result-200', 'result-100']);
    expect(screen.getByTestId('vetoed-100')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Pick Heat' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Pick Up' })).toBeOnTheScreen();
  });

  it('pick confirms in a sheet, then picks', async () => {
    show();
    muts.usePickMovie.mutate.mockImplementation((_v: unknown, o: { onSuccess: () => void }) => o.onSuccess());
    await render(<SessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Pick Up' }));
    expect(screen.getByText('Watch Up?')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Pick this' }));
    expect(muts.usePickMovie.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 200 }, expect.anything());
    expect(screen.queryByText('Watch Up?')).toBeNull();
  });

  it('pick errors stay in the sheet; closing resets', async () => {
    show();
    muts.usePickMovie.isError = true;
    muts.usePickMovie.error = new ApiError(409, 'Session is no longer open');
    await render(<SessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Pick Heat' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Session is no longer open');
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(muts.usePickMovie.reset).toHaveBeenCalled();
  });

  it('More options refreshes; errors show inline', async () => {
    show();
    await render(<SessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'More options' }));
    expect(muts.useRefreshSession.mutate).toHaveBeenCalledWith('s1');
  });

  it('shows vote and refresh failures', async () => {
    show();
    muts.useCastVote.isError = true;
    muts.useCastVote.error = new ApiError(409, 'Voting has closed');
    muts.useRefreshSession.isError = true;
    muts.useRefreshSession.error = new NetworkError();
    muts.useRefreshSession.isPending = true;
    await render(<SessionScreen />);
    const alerts = screen.getAllByRole('alert');
    expect(alerts[0]).toHaveTextContent('Voting has closed');
    expect(alerts[1]).toHaveTextContent(/Couldn't reach/);
    expect(screen.getByRole('button', { name: 'More options' })).toBeBusy();
  });

  it('opens a movie from its poster', async () => {
    show();
    await render(<SessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Up' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/200');
  });

  it('OPEN with no results suggests more options', async () => {
    show(makeSession({ results: [] }));
    await render(<SessionScreen />);
    expect(screen.getByText('Nothing fits yet')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'More options' })).toBeOnTheScreen();
  });

  it('DECIDED: picked movie hero with a rate-it-after-watching note; no voting', async () => {
    show(makeSession({ status: 'DECIDED', pickedMovie: makeMovie({ id: 200, title: 'Up', posterUrl: 'https://x/up.jpg' }) }));
    await render(<SessionScreen />);
    expect(screen.getByTestId('decided-hero')).toBeOnTheScreen();
    expect(screen.getByRole('header', { name: 'Up' })).toBeOnTheScreen();
    expect(screen.getByTestId('decided-poster')).toBeOnTheScreen();
    expect(screen.getByTestId('rate-after-watching')).toHaveTextContent(/Rate it after watching/);
    await fireEvent.press(screen.getByRole('button', { name: 'Go to Rate what you watched' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/groups');
    expect(screen.getByTestId('session-meta')).toHaveTextContent('Decided · Mean');
    expect(screen.queryByTestId('live-indicator')).toBeNull();
    expect(screen.queryByRole('button', { name: 'More options' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Thumbs/ })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Movie details' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/200');
  });

  it('EXPIRED: read-only results', async () => {
    show(makeSession({ status: 'EXPIRED' }));
    await render(<SessionScreen />);
    expect(screen.getByTestId('expired-banner')).toBeOnTheScreen();
    expect(screen.getByText('1. Heat')).toBeOnTheScreen();
    expect(screen.getAllByRole('button', { name: /^Thumbs up/ })[0]).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Pick Heat' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'More options' })).toBeNull();
    expect(screen.queryByTestId('live-indicator')).toBeNull();
  });

  it('loading, not found and error', async () => {
    m('useSession').mockReturnValue(queryResult());
    const { rerender } = await render(<SessionScreen />);
    expect(screen.getByTestId('session-loading')).toBeOnTheScreen();
    m('useSession').mockReturnValue(queryResult({ isPending: false, isError: true, error: new ApiError(404, 'x') }));
    await rerender(<SessionScreen />);
    expect(screen.getByText('Session not found')).toBeOnTheScreen();
    const refetch = jest.fn();
    m('useSession').mockReturnValue(queryResult({ isPending: false, isError: true, error: new NetworkError(), refetch }));
    await rerender(<SessionScreen />);
    expect(screen.getByText("Couldn't load this session")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('back falls back to the group after a deep link', async () => {
    show();
    routerState.router.canGoBack.mockReturnValue(false);
    await render(<SessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/groups/g1');
  });
});
