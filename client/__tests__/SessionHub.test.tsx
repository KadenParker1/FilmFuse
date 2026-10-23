jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../components/rating/PendingRatingsBanner', () => ({ PendingRatingsBanner: () => null }));
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'u1' } }) }));
jest.mock('../api/social', () => ({ useFriends: jest.fn() }));
jest.mock('../api/groups', () => ({
  ...jest.requireActual('../api/groups'),
  useGroups: jest.fn(),
  useGroup: jest.fn(),
  useAddMembers: jest.fn(),
  useRemoveMember: jest.fn(),
  useLeaveGroup: jest.fn(),
  useDeleteGroup: jest.fn(),
  useUpdateGroup: jest.fn(),
  useAcceptGroupInvite: jest.fn(),
  useDeclineGroupInvite: jest.fn(),
}));
jest.mock('../api/sessions', () => ({
  ...jest.requireActual('../api/sessions'),
  useLiveSessions: jest.fn(),
  useSessions: jest.fn(),
  useJoinSession: jest.fn(),
}));

import { fireEvent, render, screen } from '@testing-library/react-native';
import GroupsScreen from '../app/(tabs)/groups';
import GroupScreen from '../app/groups/[id]';
import * as groups from '../api/groups';
import * as sessions from '../api/sessions';
import { useFriends } from '../api/social';
import { LiveSessionCard } from '../components/sessions/LiveSessionCard';
import { SessionSummaryRow } from '../components/sessions/SessionSummaryRow';
import { ApiError } from '../lib/apiClient';
import { makeGroup, makeMovie, makeSessionSummary, makeUser } from '../test-utils/fixtures';
import { infiniteResult, mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const g = <K extends keyof typeof groups>(k: K) => groups[k] as unknown as jest.Mock;
const s = <K extends keyof typeof sessions>(k: K) => sessions[k] as unknown as jest.Mock;
let join: ReturnType<typeof mutationResult>;

const NOW = new Date('2026-10-16T12:00:00Z');
const live = (overrides = {}) =>
  makeSessionSummary({
    id: 'live1',
    status: 'OPEN',
    pickedMovie: null,
    groupName: null,
    memberCount: 4,
    votedCount: 1,
    votedByMe: false,
    closesAt: '2026-10-17T06:00:00Z',
    memberPreview: [makeUser({ id: 'u2', displayName: 'Grace Hopper' }), makeUser({ id: 'u3', displayName: 'Alan Turing' })],
    ...overrides,
  });

beforeEach(() => {
  routerState.reset();
  for (const name of [
    'useAddMembers',
    'useRemoveMember',
    'useLeaveGroup',
    'useDeleteGroup',
    'useUpdateGroup',
    'useAcceptGroupInvite',
    'useDeclineGroupInvite',
  ] as const) {
    g(name).mockReturnValue(mutationResult());
  }
  g('useGroups').mockReturnValue(queryResult({ data: [] }));
  (useFriends as jest.Mock).mockReturnValue(infiniteResult({ items: [] }));
  s('useLiveSessions').mockReturnValue(queryResult({ data: { items: [], nextCursor: null } }));
  s('useSessions').mockReturnValue(infiniteResult({ items: [] }));
  join = mutationResult();
  s('useJoinSession').mockReturnValue(join);
});

describe('LiveSessionCard', () => {
  it('shows who is in, how many voted, time left and a nudge to vote', async () => {
    const onPress = jest.fn();
    await render(<LiveSessionCard session={live()} onPress={onPress} now={NOW} />);
    const card = screen.getByRole('button', {
      name: 'Tonight, 1 of 4 voted, 18h left, waiting on your vote',
    });
    expect(screen.getByTestId('needs-vote-live1')).toHaveTextContent('Your vote');
    expect(screen.getByLabelText('Grace Hopper, Alan Turing and 2 more')).toBeOnTheScreen();
    await fireEvent.press(card);
    expect(onPress).toHaveBeenCalled();
  });

  it('no nudge once I voted; names the group', async () => {
    await render(<LiveSessionCard session={live({ votedByMe: true, groupName: 'Film club' })} onPress={jest.fn()} now={NOW} />);
    expect(screen.queryByTestId('needs-vote-live1')).toBeNull();
    expect(screen.getByRole('button', { name: 'Film club, 1 of 4 voted, 18h left' })).toBeOnTheScreen();
  });
});

describe('SessionSummaryRow', () => {
  it('shows the picked poster and, in mixed lists, the group', async () => {
    const decided = makeSessionSummary({ pickedMovie: makeMovie({ title: 'Heat', posterUrl: 'https://img/heat.jpg' }) });
    const { rerender } = await render(<SessionSummaryRow session={decided} onPress={jest.fn()} showGroup />);
    expect(screen.getByTestId('session-poster-s1')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: /^Heat, Film club · 3 people · / })).toBeOnTheScreen();
    await rerender(<SessionSummaryRow session={decided} onPress={jest.fn()} />);
    expect(screen.getByRole('button', { name: /^Heat, 3 people · / })).toBeOnTheScreen();
  });

  it('labels sessions ended early and ad-hoc ones', async () => {
    const ended = makeSessionSummary({ status: 'EXPIRED', endedEarly: true, pickedMovie: null, groupName: null });
    await render(<SessionSummaryRow session={ended} onPress={jest.fn()} showGroup />);
    expect(screen.getByRole('button', { name: /^Ended early, Tonight · / })).toBeOnTheScreen();
    expect(screen.queryByTestId('session-poster-s1')).toBeNull();
  });
});

describe('Groups tab — session hub', () => {
  it('"Live now" lists my open sessions and opens them', async () => {
    s('useLiveSessions').mockReturnValue(
      queryResult({ data: { items: [live(), live({ id: 'live2', groupName: 'Film club', votedByMe: true })], nextCursor: null } }),
    );
    await render(<GroupsScreen />);
    expect(screen.getByRole('header', { name: 'Live now · 2' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('live-session-live2'));
    expect(routerState.router.push).toHaveBeenCalledWith('/sessions/live2');
  });

  it('"Live now" is hidden with nothing open; shows loading and errors', async () => {
    const { rerender } = await render(<GroupsScreen />);
    expect(screen.queryByTestId('live-now')).toBeNull();
    s('useLiveSessions').mockReturnValue(queryResult());
    await rerender(<GroupsScreen />);
    expect(screen.getByTestId('live-loading')).toBeOnTheScreen();
    const refetch = jest.fn();
    s('useLiveSessions').mockReturnValue(
      queryResult({ isPending: false, isError: true, error: new ApiError(500, 'Down'), refetch }),
    );
    await rerender(<GroupsScreen />);
    expect(screen.getByText("Couldn't load live sessions")).toBeOnTheScreen();
  });

  it('history lists closed sessions with their picks, paged', async () => {
    const fetchNextPage = jest.fn();
    s('useSessions').mockReturnValue(
      infiniteResult({ items: [makeSessionSummary({ id: 'h1' })], hasNextPage: true, fetchNextPage }),
    );
    await render(<GroupsScreen />);
    expect(s('useSessions')).toHaveBeenCalledWith(null, 'CLOSED');
    expect(screen.getByRole('header', { name: 'Past movie nights' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('session-row-h1'));
    expect(routerState.router.push).toHaveBeenCalledWith('/sessions/h1');
    await fireEvent.press(screen.getByRole('button', { name: 'Show more' }));
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('history: empty, loading and error', async () => {
    const { rerender } = await render(<GroupsScreen />);
    expect(screen.getByTestId('no-sessions')).toHaveTextContent('No movie nights yet.');
    expect(screen.queryByRole('button', { name: 'Show more' })).toBeNull();
    s('useSessions').mockReturnValue(infiniteResult());
    await rerender(<GroupsScreen />);
    expect(screen.getByTestId('history-loading')).toBeOnTheScreen();
    s('useSessions').mockReturnValue(
      infiniteResult({ isPending: false, isError: true, error: new ApiError(500, 'Down') }),
    );
    await rerender(<GroupsScreen />);
    expect(screen.getByText("Couldn't load movie nights")).toBeOnTheScreen();
  });
});

describe('Group detail — sessions', () => {
  beforeEach(() => {
    routerState.params = { id: 'g1' };
  });

  it('pages through the group’s movie nights', async () => {
    g('useGroup').mockReturnValue(queryResult({ data: makeGroup() }));
    const fetchNextPage = jest.fn();
    s('useSessions').mockReturnValue(
      infiniteResult({ items: [makeSessionSummary({ id: 'a' }), makeSessionSummary({ id: 'b' })], hasNextPage: true, fetchNextPage }),
    );
    await render(<GroupScreen />);
    expect(s('useSessions')).toHaveBeenCalledWith('g1', null);
    expect(screen.getByRole('header', { name: 'Movie nights' })).toBeOnTheScreen();
    expect(screen.getByTestId('session-row-b')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Show more' }));
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('offers to join open sessions started before I joined', async () => {
    g('useGroup').mockReturnValue(queryResult({ data: makeGroup({ joinableSessions: [live({ id: 'j1', groupName: 'Film club' })] }) }));
    join.mutate.mockImplementation((_id: unknown, o: { onSuccess: () => void }) => o.onSuccess());
    await render(<GroupScreen />);
    expect(screen.getByRole('header', { name: 'Happening now' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Join this session' }));
    expect(join.mutate).toHaveBeenCalledWith('j1', expect.any(Object));
    expect(routerState.router.push).toHaveBeenCalledWith('/sessions/j1');
  });

  it('join errors show; nothing joinable means no section', async () => {
    join.isError = true;
    join.error = new ApiError(409, 'This session is no longer open.');
    g('useGroup').mockReturnValue(queryResult({ data: makeGroup({ joinableSessions: [live({ id: 'j1' })] }) }));
    const { rerender } = await render(<GroupScreen />);
    expect(screen.getByRole('alert')).toHaveTextContent('This session is no longer open.');
    g('useGroup').mockReturnValue(queryResult({ data: makeGroup() }));
    await rerender(<GroupScreen />);
    expect(screen.queryByTestId('joinable-sessions')).toBeNull();
  });

  it('invited members see neither joinable sessions nor history', async () => {
    g('useGroup').mockReturnValue(
      queryResult({ data: makeGroup({ role: 'MEMBER', memberStatus: 'INVITED', joinableSessions: [live()] }) }),
    );
    await render(<GroupScreen />);
    expect(screen.queryByTestId('joinable-sessions')).toBeNull();
    expect(screen.queryByTestId('session-history')).toBeNull();
  });
});
