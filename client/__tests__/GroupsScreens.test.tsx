jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
// "Rate what you watched" has its own tests (PendingRatingsBanner.test.tsx).
jest.mock('../components/rating/PendingRatingsBanner', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return { PendingRatingsBanner: () => <Text testID="pending-ratings-stub">Rate what you watched</Text> };
});
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'u1' } }) }));
jest.mock('../api/social', () => ({ useFriends: jest.fn() }));
jest.mock('../api/groups', () => ({
  ...jest.requireActual('../api/groups'),
  useGroups: jest.fn(),
  useGroup: jest.fn(),
  useCreateGroup: jest.fn(),
  useUpdateGroup: jest.fn(),
  useDeleteGroup: jest.fn(),
  useAddMembers: jest.fn(),
  useRemoveMember: jest.fn(),
  useLeaveGroup: jest.fn(),
  useAcceptGroupInvite: jest.fn(),
  useDeclineGroupInvite: jest.fn(),
}));

import { fireEvent, render, screen, within } from '@testing-library/react-native';
import GroupsScreen from '../app/(tabs)/groups';
import GroupScreen from '../app/groups/[id]';
import NewGroupScreen from '../app/groups/new';
import * as groups from '../api/groups';
import { useFriends } from '../api/social';
import { ApiError, NetworkError } from '../lib/apiClient';
import {
  makeEffectiveFilter,
  makeFriend,
  makeGroup,
  makeGroupMember,
  makeGroupSummary,
  makeSessionSummary,
} from '../test-utils/fixtures';
import { infiniteResult, mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const m = <K extends keyof typeof groups>(k: K) => groups[k] as unknown as jest.Mock;
let muts: Record<string, ReturnType<typeof mutationResult>>;
const MUTATIONS = [
  'useCreateGroup',
  'useUpdateGroup',
  'useDeleteGroup',
  'useAddMembers',
  'useRemoveMember',
  'useLeaveGroup',
  'useAcceptGroupInvite',
  'useDeclineGroupInvite',
] as const;

const friends = Array.from({ length: 20 }, (_, i) =>
  makeFriend({ id: `f${i}`, displayName: `Friend ${i}`, username: `friend${i}` }),
);

beforeEach(() => {
  routerState.reset();
  muts = {};
  for (const name of MUTATIONS) {
    muts[name] = mutationResult();
    m(name).mockImplementation(() => muts[name]);
  }
  (useFriends as jest.Mock).mockReturnValue(infiniteResult({ items: friends }));
});

// ---- Groups tab -------------------------------------------------------------------------------

describe('GroupsScreen', () => {
  it('shows the Tonight CTA, invites and groups', async () => {
    m('useGroups').mockReturnValue(
      queryResult({
        data: [
          makeGroupSummary(),
          makeGroupSummary({ id: 'g2', name: 'Cousins', memberStatus: 'INVITED', role: 'MEMBER' }),
        ],
      }),
    );
    await render(<GroupsScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Tonight: start a movie night with friends' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/sessions/new');
    const invites = screen.getByTestId('group-invites');
    expect(within(invites).getByText('Invites · 1')).toBeOnTheScreen();
    expect(within(invites).getByText('Cousins')).toBeOnTheScreen();
    expect(within(screen.getByTestId('group-list')).getByText('Film club')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: /^Film club/ }));
    expect(routerState.router.push).toHaveBeenCalledWith('/groups/g1');
    await fireEvent.press(screen.getByRole('button', { name: 'Create a group' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/groups/new');
  });

  it('accepts (then opens) and declines invites', async () => {
    m('useGroups').mockReturnValue(
      queryResult({ data: [makeGroupSummary({ id: 'g2', name: 'Cousins', memberStatus: 'INVITED' })] }),
    );
    muts.useAcceptGroupInvite!.mutate.mockImplementation((_id: string, o: { onSuccess: () => void }) => o.onSuccess());
    await render(<GroupsScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Accept invite to Cousins' }));
    expect(muts.useAcceptGroupInvite!.mutate).toHaveBeenCalledWith('g2', expect.anything());
    expect(routerState.router.push).toHaveBeenCalledWith('/groups/g2');
    await fireEvent.press(screen.getByRole('button', { name: 'Decline invite to Cousins' }));
    expect(muts.useDeclineGroupInvite!.mutate).toHaveBeenCalledWith('g2');
  });

  it('shows invite errors and busy rows', async () => {
    m('useGroups').mockReturnValue(
      queryResult({ data: [makeGroupSummary({ id: 'g2', name: 'Cousins', memberStatus: 'INVITED' })] }),
    );
    muts.useDeclineGroupInvite!.isPending = true;
    muts.useDeclineGroupInvite!.variables = 'g2';
    muts.useAcceptGroupInvite!.isError = true;
    muts.useAcceptGroupInvite!.error = new ApiError(404, 'Invite no longer exists');
    await render(<GroupsScreen />);
    expect(screen.getByRole('alert')).toHaveTextContent('Invite no longer exists');
    expect(screen.getByRole('button', { name: 'Decline invite to Cousins' })).toBeBusy();
    expect(screen.getByRole('button', { name: 'Accept invite to Cousins' })).toBeDisabled();
  });

  it('empty state offers Create a group', async () => {
    m('useGroups').mockReturnValue(queryResult({ data: [] }));
    await render(<GroupsScreen />);
    expect(screen.getByText('No groups yet')).toBeOnTheScreen();
    expect(screen.queryByTestId('group-invites')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Create a group' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/groups/new');
  });

  it('loading keeps the Tonight CTA available', async () => {
    m('useGroups').mockReturnValue(queryResult());
    await render(<GroupsScreen />);
    expect(screen.getByTestId('groups-loading')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: /^Tonight/ })).toBeOnTheScreen();
    // "Rate what you watched" doesn't wait for the groups.
    expect(screen.getByTestId('pending-ratings-stub')).toBeOnTheScreen();
  });

  it('error with retry', async () => {
    const refetch = jest.fn();
    m('useGroups').mockReturnValue(queryResult({ isPending: false, isError: true, error: new NetworkError(), refetch }));
    await render(<GroupsScreen />);
    expect(screen.getByText("Couldn't load your groups")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });
});

// ---- Create group -----------------------------------------------------------------------------

describe('NewGroupScreen', () => {
  it('requires a name', async () => {
    await render(<NewGroupScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Create group' }));
    expect(screen.getByText('Give your group a name.')).toBeOnTheScreen();
    expect(muts.useCreateGroup!.mutate).not.toHaveBeenCalled();
    await fireEvent.changeText(screen.getByLabelText('Group name'), 'C');
    expect(screen.queryByText('Give your group a name.')).toBeNull();
  });

  it('rejects names over 40 characters', async () => {
    await render(<NewGroupScreen />);
    await fireEvent.changeText(screen.getByLabelText('Group name'), 'x'.repeat(41));
    await fireEvent.press(screen.getByRole('button', { name: 'Create group' }));
    expect(screen.getByText('Keep it to 40 characters.')).toBeOnTheScreen();
  });

  it('caps the invite list at 19 friends', async () => {
    await render(<NewGroupScreen />);
    for (let i = 0; i < 19; i += 1) await fireEvent.press(screen.getByRole('checkbox', { name: `Friend ${i}` }));
    expect(screen.getByTestId('friend-picker-count')).toHaveTextContent('19 of 19 selected');
    expect(screen.getByRole('checkbox', { name: 'Friend 19' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Friend 19' }));
    expect(screen.getByTestId('friend-picker-count')).toHaveTextContent('19 of 19 selected');
  });

  it('creates with name, emoji, colour and friends, then opens the group', async () => {
    muts.useCreateGroup!.mutate.mockImplementation((_b: unknown, o: { onSuccess: (g: { id: string }) => void }) =>
      o.onSuccess({ id: 'g9' }),
    );
    await render(<NewGroupScreen />);
    await fireEvent.changeText(screen.getByLabelText('Group name'), '  Friday club ');
    await fireEvent.press(screen.getByRole('radio', { name: 'Emoji 👻' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Ticket green' }));
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Friend 3' }));
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Friend 5' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Create group' }));
    expect(muts.useCreateGroup!.mutate).toHaveBeenCalledWith(
      { name: 'Friday club', emoji: '👻', color: '#4CC38A', memberIds: ['f3', 'f5'] },
      expect.anything(),
    );
    expect(routerState.router.replace).toHaveBeenCalledWith('/groups/g9');
  });

  it('creates a group with nobody else yet', async () => {
    await render(<NewGroupScreen />);
    await fireEvent.changeText(screen.getByLabelText('Group name'), 'Solo');
    await fireEvent.press(screen.getByRole('button', { name: 'Create group' }));
    expect(muts.useCreateGroup!.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Solo', memberIds: [] }),
      expect.anything(),
    );
  });

  it('shows server errors (name field and general) and busy state', async () => {
    muts.useCreateGroup!.isError = true;
    muts.useCreateGroup!.error = new ApiError(400, 'Validation failed', { name: 'Too long' });
    const { rerender } = await render(<NewGroupScreen />);
    expect(screen.getByText('Too long')).toBeOnTheScreen();
    muts.useCreateGroup!.error = new ApiError(400, 'u7 is not your friend');
    muts.useCreateGroup!.isPending = true;
    await rerender(<NewGroupScreen />);
    expect(screen.getByRole('alert')).toHaveTextContent('u7 is not your friend');
    expect(screen.getByRole('button', { name: 'Create group' })).toBeBusy();
  });
});

// ---- Group detail -----------------------------------------------------------------------------

describe('GroupScreen', () => {
  beforeEach(() => {
    routerState.params = { id: 'g1' };
  });
  const show = (group = makeGroup()) => m('useGroup').mockReturnValue(queryResult({ data: group }));

  it('owner: header, filter banner, members with roles, start session, history', async () => {
    show(
      makeGroup({
        effectiveFilter: makeEffectiveFilter({ maxCertification: 'PG', label: 'Up to PG' }),
        recentSessions: [makeSessionSummary()],
      }),
    );
    await render(<GroupScreen />);
    expect(m('useGroup')).toHaveBeenCalledWith('g1');
    expect(screen.getByRole('header', { name: 'Film club' })).toBeOnTheScreen();
    expect(screen.getByText('2 members')).toBeOnTheScreen();
    expect(screen.getByText('Filtered to Up to PG')).toBeOnTheScreen();
    expect(screen.getByText('Owner')).toBeOnTheScreen();
    expect(screen.getByText('Invited')).toBeOnTheScreen();
    // Owner can remove others, not themselves.
    expect(screen.getByRole('button', { name: 'Remove Grace Hopper' })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Remove Alan Turing' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Remove Ada Lovelace' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Group settings' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Start session' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/sessions/new?groupId=g1');
    await fireEvent.press(screen.getByTestId('session-row-s1'));
    expect(routerState.router.push).toHaveBeenCalledWith('/sessions/s1');
  });

  it('owner removes a member after confirming', async () => {
    show();
    muts.useRemoveMember!.mutate.mockImplementation((_v: unknown, o: { onSuccess: () => void }) => o.onSuccess());
    await render(<GroupScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Remove Grace Hopper' }));
    expect(screen.getByText('Remove Grace Hopper?')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Remove' }));
    expect(muts.useRemoveMember!.mutate).toHaveBeenCalledWith({ groupId: 'g1', userId: 'u2' }, expect.anything());
    expect(screen.queryByText('Remove Grace Hopper?')).toBeNull();
  });

  it('confirm sheet shows errors and can be cancelled', async () => {
    show();
    muts.useRemoveMember!.isError = true;
    muts.useRemoveMember!.error = new ApiError(403, 'Only the owner can remove members');
    await render(<GroupScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Remove Grace Hopper' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Only the owner can remove members');
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(muts.useRemoveMember!.reset).toHaveBeenCalled();
    expect(screen.queryByText('Remove Grace Hopper?')).toBeNull();
  });

  it('owner opens settings and can delete the group', async () => {
    show();
    muts.useDeleteGroup!.mutate.mockImplementation((_v: unknown, o: { onSuccess: () => void }) => o.onSuccess());
    await render(<GroupScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Group settings' }));
    expect(screen.getByText('Group comfort cap')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Delete group' }));
    expect(screen.getByText('Delete Film club?')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Delete group' }));
    expect(muts.useDeleteGroup!.mutate).toHaveBeenCalledWith('g1', expect.anything());
    expect(routerState.router.replace).toHaveBeenCalledWith('/groups');
  });

  it('owner leaving is warned that ownership passes on', async () => {
    show();
    await render(<GroupScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Leave group' }));
    expect(screen.getByText(/Ownership passes/)).toBeOnTheScreen();
  });

  it('member: no settings or remove buttons; can leave', async () => {
    show(makeGroup({ role: 'MEMBER', ownerId: 'u2' }));
    muts.useLeaveGroup!.mutate.mockImplementation((_v: unknown, o: { onSuccess: () => void }) => o.onSuccess());
    await render(<GroupScreen />);
    expect(screen.queryByRole('button', { name: 'Group settings' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Remove / })).toBeNull();
    expect(screen.getByRole('button', { name: 'Start session' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Leave group' }));
    expect(screen.getByText('Leave Film club?')).toBeOnTheScreen();
    expect(screen.queryByText(/Ownership passes/)).toBeNull();
    await fireEvent.press(screen.getAllByRole('button', { name: 'Leave group' }).at(-1)!);
    expect(muts.useLeaveGroup!.mutate).toHaveBeenCalledWith({ groupId: 'g1', userId: 'u1' }, expect.anything());
    expect(routerState.router.replace).toHaveBeenCalledWith('/groups');
  });

  it('member invites more friends (excluding current members, capped by room)', async () => {
    show(
      makeGroup({
        role: 'MEMBER',
        memberCount: 2,
        invitedCount: 0,
        members: [makeGroupMember({ id: 'u1' }, { role: 'OWNER' }), makeGroupMember({ id: 'f0', displayName: 'Friend 0' })],
      }),
    );
    muts.useAddMembers!.mutate.mockImplementation((_v: unknown, o: { onSuccess: () => void }) => o.onSuccess());
    await render(<GroupScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Invite friends' }));
    expect(screen.queryByRole('checkbox', { name: 'Friend 0' })).toBeNull();
    expect(screen.getByTestId('friend-picker-count')).toHaveTextContent('0 of 18 selected');
    expect(screen.getByRole('button', { name: 'Invite' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Friend 4' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Invite 1 friend' }));
    expect(muts.useAddMembers!.mutate).toHaveBeenCalledWith({ groupId: 'g1', userIds: ['f4'] }, expect.anything());
    expect(screen.queryByTestId('friend-picker-count')).toBeNull();
  });

  it('a full group says so', async () => {
    show(
      makeGroup({
        memberCount: 20,
        invitedCount: 0,
        members: Array.from({ length: 20 }, (_, i) => makeGroupMember({ id: `x${i}` })),
      }),
    );
    await render(<GroupScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Invite friends' }));
    expect(screen.getByText('This group is full (20 people).')).toBeOnTheScreen();
  });

  it('counts members hidden from the viewer (blocked either way) for room and the member count', async () => {
    // 18 active + 2 invited on the server, but only 2 of them are visible to this viewer.
    show(makeGroup({ memberCount: 18, invitedCount: 2 }));
    await render(<GroupScreen />);
    expect(screen.getByText('18 members')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Invite friends' }));
    expect(screen.getByText('This group is full (20 people).')).toBeOnTheScreen();
  });

  it('invited: Accept / Decline instead of session controls', async () => {
    show(makeGroup({ role: 'MEMBER', memberStatus: 'INVITED' }));
    muts.useDeclineGroupInvite!.mutate.mockImplementation((_v: unknown, o: { onSuccess: () => void }) => o.onSuccess());
    await render(<GroupScreen />);
    expect(screen.getByTestId('group-invite-card')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Start session' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Leave group' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Invite friends' })).toBeNull();
    expect(screen.queryByTestId('filter-banner')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Accept invite' }));
    expect(muts.useAcceptGroupInvite!.mutate).toHaveBeenCalledWith('g1');
    await fireEvent.press(screen.getByRole('button', { name: 'Decline invite' }));
    expect(muts.useDeclineGroupInvite!.mutate).toHaveBeenCalledWith('g1', expect.anything());
    expect(routerState.router.replace).toHaveBeenCalledWith('/groups');
  });

  it('invited: shows accept errors', async () => {
    show(makeGroup({ role: 'MEMBER', memberStatus: 'INVITED' }));
    muts.useAcceptGroupInvite!.isError = true;
    muts.useAcceptGroupInvite!.error = new ApiError(409, 'Group is full');
    await render(<GroupScreen />);
    expect(screen.getByRole('alert')).toHaveTextContent('Group is full');
  });

  it('no sessions yet', async () => {
    show();
    await render(<GroupScreen />);
    expect(screen.getByTestId('no-sessions')).toHaveTextContent('No movie nights yet.');
  });

  it('loading, not found and error', async () => {
    m('useGroup').mockReturnValue(queryResult());
    const { rerender } = await render(<GroupScreen />);
    expect(screen.getByTestId('group-loading')).toBeOnTheScreen();
    m('useGroup').mockReturnValue(queryResult({ isPending: false, isError: true, error: new ApiError(404, 'nope') }));
    await rerender(<GroupScreen />);
    expect(screen.getByText('Group not found')).toBeOnTheScreen();
    const refetch = jest.fn();
    m('useGroup').mockReturnValue(queryResult({ isPending: false, isError: true, error: new NetworkError(), refetch }));
    await rerender(<GroupScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });
});
