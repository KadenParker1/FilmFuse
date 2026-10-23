jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/social', () => ({ useFriends: jest.fn() }));
jest.mock('../api/sessionLive', () => ({ useSessionLive: jest.fn(() => ({ mode: 'live' })) }));
jest.mock('../api/sessions', () => ({
  ...jest.requireActual('../api/sessions'),
  useSession: jest.fn(),
  useCastVote: jest.fn(),
  useClearVote: jest.fn(),
  useRefreshSession: jest.fn(),
  usePickMovie: jest.fn(),
  useEndSession: jest.fn(),
  useJoinSession: jest.fn(),
  useAddSessionMembers: jest.fn(),
}));
let mockUserId = 'u1';
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ user: { id: mockUserId } }) }));
jest.mock('../components/sessions/shareSession', () => ({ shareSession: jest.fn() }));

import { AccessibilityInfo, Platform } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import SessionScreen from '../app/sessions/[id]';
import * as sessions from '../api/sessions';
import { useFriends } from '../api/social';
import { AddPeopleSheet } from '../components/sessions/AddPeopleSheet';
import { SessionMembersRow } from '../components/sessions/SessionMembersRow';
import { SessionResultCard } from '../components/sessions/SessionResultCard';
import { ShareSessionButton } from '../components/sessions/ShareSessionButton';
import { TallyPanel } from '../components/sessions/TallyPanel';
import { shareSession } from '../components/sessions/shareSession';
import { computeTally } from '../components/sessions/tally';
import { ApiError } from '../lib/apiClient';
import { makeFriend, makeMovie, makeResult, makeSession, makeSessionMember } from '../test-utils/fixtures';
import { infiniteResult, mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';
import type { SessionResultDTO } from '../types/api';

const m = <K extends keyof typeof sessions>(k: K) => sessions[k] as unknown as jest.Mock;
const MUTATIONS = [
  'useCastVote',
  'useClearVote',
  'useRefreshSession',
  'usePickMovie',
  'useEndSession',
  'useJoinSession',
  'useAddSessionMembers',
] as const;
let muts: Record<(typeof MUTATIONS)[number], ReturnType<typeof mutationResult>>;

beforeEach(() => {
  routerState.reset();
  routerState.params = { id: 's1' };
  mockUserId = 'u1';
  muts = {} as typeof muts;
  for (const name of MUTATIONS) {
    muts[name] = mutationResult();
    m(name).mockImplementation(() => muts[name]);
  }
  (useFriends as jest.Mock).mockReturnValue(
    infiniteResult({
      items: [makeFriend({ id: 'f1', displayName: 'Joan Clarke' }), makeFriend({ id: 'u2', displayName: 'Grace Hopper' })],
    }),
  );
  (shareSession as jest.Mock).mockReset();
});

const show = (session = makeSession()) => m('useSession').mockReturnValue(queryResult({ data: session }));
const res = (id: number, rank: number, up: number, down = 0, extra: Partial<SessionResultDTO> = {}) =>
  makeResult({ rank, movie: makeMovie({ id, title: `Movie ${id}` }), votes: { up, down, vetoed: false }, ...extra });

// ---- components -------------------------------------------------------------------------------

describe('TallyPanel', () => {
  it('shows the leader in a polite live region and picks it', async () => {
    const leader = res(2, 2, 2);
    const onPick = jest.fn();
    await render(<TallyPanel tally={computeTally([res(1, 1, 1), leader], 3, 2)} onPickLeader={onPick} />);
    const region = screen.getByTestId('tally-live-region');
    expect(region).toHaveTextContent('Leading: Movie 2, 2 up, 0 down.');
    expect(region.props.accessibilityLiveRegion).toBe('polite');
    expect(region.props['aria-live']).toBe('polite');
    expect(screen.queryByTestId('tie-note')).toBeNull();
    expect(screen.queryByTestId('everyone-voted')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Pick the leader, Movie 2' }));
    expect(onPick).toHaveBeenCalledWith(leader);
  });

  it('explains ties, everyone-likes and everyone-voted', async () => {
    await render(<TallyPanel tally={computeTally([res(1, 1, 2), res(2, 2, 2)], 2, 2)} onPickLeader={jest.fn()} />);
    expect(screen.getByTestId('tally-live-region')).toHaveTextContent(/^Tied: Movie 1 and Movie 2/);
    expect(screen.getByTestId('tie-note')).toHaveTextContent(/higher-ranked pick goes first/);
    expect(screen.getByTestId('leader-everyone-likes')).toBeOnTheScreen();
    expect(screen.getByTestId('everyone-voted')).toHaveTextContent(/Everyone’s voted/);
  });

  it('has no shortcut before any thumbs-up', async () => {
    await render(<TallyPanel tally={computeTally([res(1, 1, 0)], 2, 0)} onPickLeader={jest.fn()} />);
    expect(screen.getByTestId('tally-live-region')).toHaveTextContent('No thumbs-up yet.');
    expect(screen.queryByRole('button', { name: /Pick the leader/ })).toBeNull();
  });

  it('on iOS announces changes (not the first render) for VoiceOver', async () => {
    const originalOS = Platform.OS;
    Object.defineProperty(Platform, 'OS', { value: 'ios', configurable: true });
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
    try {
      const { rerender } = await render(
        <TallyPanel tally={computeTally([res(1, 1, 1)], 3, 1)} onPickLeader={jest.fn()} />,
      );
      expect(announce).not.toHaveBeenCalled();
      await rerender(<TallyPanel tally={computeTally([res(1, 1, 1)], 3, 1)} onPickLeader={jest.fn()} />);
      expect(announce).not.toHaveBeenCalled();
      await rerender(<TallyPanel tally={computeTally([res(1, 1, 1), res(2, 2, 2)], 3, 2)} onPickLeader={jest.fn()} />);
      expect(announce).toHaveBeenCalledWith('Leading: Movie 2, 2 up, 0 down.');
    } finally {
      announce.mockRestore();
      Object.defineProperty(Platform, 'OS', { value: originalOS, configurable: true });
    }
  });
});

describe('SessionResultCard', () => {
  const props = { members: [], onVote: jest.fn(), onClearVote: jest.fn(), onPick: jest.fn() };

  it('marks the leader, ties and everyone-likes', async () => {
    const { rerender } = await render(<SessionResultCard {...props} result={res(1, 1, 3)} leader everyoneLikes />);
    expect(screen.getByTestId('leader-1')).toHaveTextContent('Leading');
    expect(screen.getByTestId('everyone-likes-1')).toHaveTextContent('Everyone likes this');
    await rerender(<SessionResultCard {...props} result={res(1, 1, 3)} tied />);
    expect(screen.getByTestId('tied-1')).toHaveTextContent('Tied');
    expect(screen.queryByTestId('leader-1')).toBeNull();
    await rerender(<SessionResultCard {...props} result={res(1, 1, 3)} />);
    expect(screen.queryByTestId('tied-1')).toBeNull();
    expect(screen.queryByTestId('everyone-likes-1')).toBeNull();
  });

  it('never marks a vetoed result', async () => {
    const vetoed = makeResult({ votes: { up: 3, down: 0, vetoed: true } });
    await render(<SessionResultCard {...props} result={vetoed} leader everyoneLikes />);
    expect(screen.queryByTestId('leader-100')).toBeNull();
    expect(screen.queryByTestId('everyone-likes-100')).toBeNull();
  });

  it('Pick this and the vote buttons are at least 44px tall', async () => {
    await render(<SessionResultCard {...props} result={res(1, 1, 0)} />);
    expect(screen.getByRole('button', { name: 'Pick Movie 1' }).props.className).toMatch(/\bmin-h-11\b/);
    expect(screen.getByRole('button', { name: 'Thumbs up, 0' }).props.className).toMatch(/\bmin-h-11\b/);
  });
});

describe('SessionMembersRow', () => {
  it('names who the vote is waiting on only when asked', async () => {
    const members = [
      makeSessionMember({ id: 'u1', displayName: 'Ada' }, { voted: true }),
      makeSessionMember({ id: 'u2', displayName: 'Grace' }),
      makeSessionMember({ id: 'u3', displayName: 'Alan' }),
    ];
    const { rerender } = await render(<SessionMembersRow members={members} showWaiting />);
    expect(screen.getByTestId('waiting-on')).toHaveTextContent('Waiting on Grace, Alan');
    expect(screen.getByTestId('voted-u1')).toBeOnTheScreen();
    await rerender(<SessionMembersRow members={members} />);
    expect(screen.queryByTestId('waiting-on')).toBeNull();
  });
});

describe('ShareSessionButton', () => {
  it('says when the link was copied instead', async () => {
    (shareSession as jest.Mock).mockResolvedValue('copied');
    await render(<ShareSessionButton url="https://x/sessions/s1" />);
    await fireEvent.press(screen.getByRole('button', { name: 'Share this session' }));
    expect(shareSession).toHaveBeenCalledWith('https://x/sessions/s1');
    await waitFor(() => expect(screen.getByTestId('share-note')).toHaveTextContent('Link copied.'));
  });

  it('stays quiet after a native share and shows failures', async () => {
    (shareSession as jest.Mock).mockResolvedValue('shared');
    await render(<ShareSessionButton url="u" />);
    await fireEvent.press(screen.getByRole('button', { name: 'Share this session' }));
    await waitFor(() => expect(shareSession).toHaveBeenCalled());
    expect(screen.queryByTestId('share-note')).toBeNull();
    (shareSession as jest.Mock).mockRejectedValue(new Error('Clipboard blocked'));
    await fireEvent.press(screen.getByRole('button', { name: 'Share this session' }));
    await waitFor(() => expect(screen.getByTestId('share-note')).toHaveTextContent('Clipboard blocked'));
  });
});

describe('AddPeopleSheet', () => {
  it('adds friends who are not in the session yet', async () => {
    muts.useAddSessionMembers.mutate.mockImplementation((_v: unknown, o: { onSuccess: () => void }) => o.onSuccess());
    const onClose = jest.fn();
    await render(<AddPeopleSheet session={makeSession()} visible onClose={onClose} />);
    // Grace (u2) is already a member.
    expect(screen.queryByRole('checkbox', { name: 'Grace Hopper' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Joan Clarke' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Add 1 friend' }));
    expect(muts.useAddSessionMembers.mutate).toHaveBeenCalledWith(
      { sessionId: 's1', userIds: ['f1'] },
      expect.any(Object),
    );
    expect(onClose).toHaveBeenCalled();
  });

  it('a full session says so; errors show', async () => {
    muts.useAddSessionMembers.isError = true;
    muts.useAddSessionMembers.error = new ApiError(400, 'You can only add your friends to a group.');
    await render(<AddPeopleSheet session={makeSession({ memberCount: 20 })} visible onClose={jest.fn()} />);
    expect(screen.getByText('This session is full (20 people).')).toBeOnTheScreen();
    expect(screen.getByRole('alert')).toHaveTextContent('You can only add your friends to a group.');
  });
});

// ---- the session screen -----------------------------------------------------------------------

describe('SessionScreen — live tally', () => {
  it('shows the tally overlay without reordering the ranked results', async () => {
    show(makeSession({ results: [res(1, 1, 0), res(2, 2, 2)], memberCount: 2, votedCount: 2 }));
    await render(<SessionScreen />);
    expect(screen.getByTestId('tally-live-region')).toHaveTextContent(/Leading: Movie 2/);
    expect(screen.getByTestId('everyone-voted')).toBeOnTheScreen();
    expect(screen.getByTestId('leader-2')).toBeOnTheScreen();
    expect(screen.getByTestId('everyone-likes-2')).toBeOnTheScreen();
    const order = screen.getAllByTestId(/^result-\d+$/).map((n) => n.props.testID);
    expect(order).toEqual(['result-1', 'result-2']);
  });

  it('marks every tied result and "Pick the leader" confirms the rank-first one', async () => {
    show(makeSession({ results: [res(1, 1, 1), res(2, 2, 1), res(3, 3, 0)] }));
    await render(<SessionScreen />);
    expect(screen.getByTestId('tied-1')).toBeOnTheScreen();
    expect(screen.getByTestId('tied-2')).toBeOnTheScreen();
    expect(screen.queryByTestId('tied-3')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Pick the leader, Movie 1' }));
    expect(screen.getByText('Watch Movie 1?')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Pick this' }));
    expect(muts.usePickMovie.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 1 }, expect.any(Object));
  });

  it('names who still has to vote and shows a quiet countdown', async () => {
    show(makeSession({ closesAt: new Date(Date.now() + 5 * 3_600_000 + 12 * 60_000 + 30_000).toISOString() }));
    await render(<SessionScreen />);
    expect(screen.getByTestId('waiting-on')).toHaveTextContent('Waiting on Ada Lovelace');
    expect(screen.getByTestId('session-meta')).toHaveTextContent('Voting open · Mean');
    const countdown = screen.getByTestId('session-countdown');
    expect(countdown).toHaveTextContent('Closes in 5h 12m');
    expect(countdown.props.accessibilityLabel).toBe('Voting closes in 5 hours 12 minutes');
    // Never a live region: it would announce every tick.
    expect(countdown.props.accessibilityLiveRegion).toBe('none');
    expect(countdown.props['aria-live']).toBe('off');
  });

  it('the countdown ticks every second in the last hour and refetches the session at zero', async () => {
    jest.useFakeTimers({ now: new Date('2026-10-16T12:00:00Z') });
    try {
      const refetch = jest.fn();
      m('useSession').mockReturnValue(
        queryResult({ data: makeSession({ closesAt: '2026-10-16T12:02:05Z' }), refetch }),
      );
      await render(<SessionScreen />);
      const countdown = () => screen.getByTestId('session-countdown');
      expect(countdown()).toHaveTextContent('Closes in 2m 05s');
      await act(() => jest.advanceTimersByTime(1_000));
      expect(countdown()).toHaveTextContent('Closes in 2m 04s');
      expect(countdown().props.accessibilityLabel).toBe('Voting closes in 2 minutes');
      await act(() => jest.advanceTimersByTime(124_001));
      expect(countdown()).toHaveTextContent('Closing…');
      expect(refetch).toHaveBeenCalledTimes(1);
    } finally {
      jest.useRealTimers();
    }
  });

  it('closed sessions show no countdown', async () => {
    show(makeSession({ status: 'EXPIRED' }));
    await render(<SessionScreen />);
    expect(screen.queryByTestId('session-countdown')).toBeNull();
  });

  it('explains hidden results without naming anyone, and offers more options', async () => {
    show(makeSession({ hiddenResultCount: 2 }));
    await render(<SessionScreen />);
    expect(screen.getByTestId('hidden-results')).toHaveTextContent(
      /^2 picks no longer fit everyone’s comfort settings, so they’re hidden\./,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Get more options' }));
    expect(muts.useRefreshSession.mutate).toHaveBeenCalledWith('s1');
  });

  it('tells a late joiner the picks predate them', async () => {
    mockUserId = 'u9';
    show(
      makeSession({
        members: [makeSessionMember({ id: 'u1' }), makeSessionMember({ id: 'u9', displayName: 'Late' })],
      }),
    );
    await render(<SessionScreen />);
    expect(screen.getByTestId('joined-late')).toBeOnTheScreen();
  });

  it('no late-joiner note for an original member', async () => {
    show();
    await render(<SessionScreen />);
    expect(screen.queryByTestId('joined-late')).toBeNull();
    expect(screen.queryByTestId('hidden-results')).toBeNull();
  });
});

describe('SessionScreen — share, add people, end', () => {
  it('the creator can share, add people and end the session after confirming', async () => {
    show();
    muts.useEndSession.mutate.mockImplementation((_v: unknown, o: { onSuccess: () => void }) => o.onSuccess());
    await render(<SessionScreen />);
    expect(screen.getByRole('button', { name: 'Share this session' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Add people' }));
    expect(screen.getByRole('checkbox', { name: 'Joan Clarke' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'End session' }));
    expect(screen.getByText('End this session?')).toBeOnTheScreen();
    const confirm = screen.getAllByRole('button', { name: 'End session' });
    await fireEvent.press(confirm[confirm.length - 1]!);
    expect(muts.useEndSession.mutate).toHaveBeenCalledWith('s1', expect.any(Object));
  });

  it('shares the server-provided web link', async () => {
    (shareSession as jest.Mock).mockResolvedValue('shared');
    show();
    await render(<SessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Share this session' }));
    expect(shareSession).toHaveBeenCalledWith('https://filmfuse.test/sessions/s1');
  });

  it('other members cannot end it', async () => {
    mockUserId = 'u2';
    show();
    await render(<SessionScreen />);
    expect(screen.queryByRole('button', { name: 'End session' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Add people' })).toBeOnTheScreen();
  });

  it('closed sessions keep Share but not Add people or End', async () => {
    show(makeSession({ status: 'DECIDED', pickedMovie: makeMovie() }));
    await render(<SessionScreen />);
    expect(screen.getByRole('button', { name: 'Share this session' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Add people' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'End session' })).toBeNull();
  });

  it('a session ended early says so instead of "expired"', async () => {
    show(makeSession({ status: 'EXPIRED', endedEarly: true }));
    await render(<SessionScreen />);
    expect(screen.getByTestId('session-meta')).toHaveTextContent(/^Ended early · Mean$/);
    expect(screen.getByTestId('expired-banner')).toHaveTextContent('This session was ended early — nothing was picked.');
    expect(screen.queryByTestId('tally-panel')).toBeNull();
  });

  it('end errors stay in the sheet', async () => {
    show();
    muts.useEndSession.isError = true;
    muts.useEndSession.error = new ApiError(409, 'This session is no longer open.');
    await render(<SessionScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'End session' }));
    expect(screen.getByRole('alert')).toHaveTextContent('This session is no longer open.');
  });
});

describe('SessionScreen — joining', () => {
  it('a group member not in the session yet (403) can join it', async () => {
    m('useSession').mockReturnValue(
      queryResult({
        isPending: false,
        isError: true,
        error: new ApiError(403, "You're not in this session yet. Join it to vote."),
      }),
    );
    await render(<SessionScreen />);
    expect(screen.getByTestId('join-session')).toHaveTextContent(/not in this session yet/);
    await fireEvent.press(screen.getByRole('button', { name: 'Join session' }));
    expect(muts.useJoinSession.mutate).toHaveBeenCalledWith('s1');
  });

  it('join errors show; a 404 suggests asking to be added', async () => {
    muts.useJoinSession.isError = true;
    muts.useJoinSession.error = new ApiError(409, 'A session can have at most 20 people.');
    m('useSession').mockReturnValue(
      queryResult({ isPending: false, isError: true, error: new ApiError(403, 'Join it.') }),
    );
    const { rerender } = await render(<SessionScreen />);
    expect(screen.getByRole('alert')).toHaveTextContent('A session can have at most 20 people.');
    m('useSession').mockReturnValue(queryResult({ isPending: false, isError: true, error: new ApiError(404, 'nope') }));
    await rerender(<SessionScreen />);
    expect(screen.getByText('Only people in a session can see it. Ask someone in it to add you.')).toBeOnTheScreen();
  });
});
