jest.mock('../api/social', () => ({ useSendFriendRequest: jest.fn(), useAcceptRequest: jest.fn() }));
jest.mock('../api/invites', () => ({ useCreateInvite: jest.fn() }));
jest.mock('../components/social/shareInvite', () => ({ shareInvite: jest.fn() }));

import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Text } from 'react-native';
import { useCreateInvite } from '../api/invites';
import { useAcceptRequest, useSendFriendRequest } from '../api/social';
import { CloseFriendStar } from '../components/social/CloseFriendStar';
import { FriendshipActionButton, StatusPill } from '../components/social/FriendshipActionButton';
import { InviteCard } from '../components/social/InviteCard';
import { inviteFailureCopy } from '../components/social/inviteFailureCopy';
import { shareInvite } from '../components/social/shareInvite';
import { UserRow, UserRowSkeletons } from '../components/social/UserRow';
import { Button, SegmentedControl } from '../components/ui';
import { ApiError, NetworkError } from '../lib/apiClient';
import { makeUser } from '../test-utils/fixtures';
import { mutationResult } from '../test-utils/queryResults';
import type { FriendshipState } from '../types/api';

const mockSend = useSendFriendRequest as jest.Mock;
const mockAccept = useAcceptRequest as jest.Mock;
const mockCreate = useCreateInvite as jest.Mock;
const mockShare = shareInvite as jest.Mock;

let send: ReturnType<typeof mutationResult>;
let accept: ReturnType<typeof mutationResult>;
beforeEach(() => {
  send = mutationResult();
  accept = mutationResult();
  mockSend.mockImplementation(() => send);
  mockAccept.mockImplementation(() => accept);
  mockShare.mockReset();
});

describe('UserRow', () => {
  it('shows avatar, display name, @username and subtitle, and opens on press', async () => {
    const onPress = jest.fn();
    await render(
      <UserRow user={makeUser()} subtitle="3 mutual friends" onPress={onPress}>
        <Text>action</Text>
      </UserRow>,
    );
    expect(screen.getByText('Grace Hopper')).toBeOnTheScreen();
    expect(screen.getByText('@grace')).toBeOnTheScreen();
    expect(screen.getByText('3 mutual friends')).toBeOnTheScreen();
    expect(screen.getByLabelText("Grace Hopper's profile photo")).toBeOnTheScreen();
    expect(screen.getByText('action')).toBeOnTheScreen();
    const row = screen.getByRole('button', { name: 'Grace Hopper, @grace' });
    expect(row).toHaveProp('accessibilityHint', 'Opens their profile');
    await fireEvent.press(row);
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('falls back to @username as the name and is not a button without onPress', async () => {
    await render(<UserRow user={makeUser({ displayName: null })} />);
    expect(screen.getByText('@grace')).toBeOnTheScreen();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByLabelText('@grace')).toBeOnTheScreen();
  });

  it('without a username shows the display name only, never "@null"', async () => {
    await render(<UserRow user={makeUser({ username: null })} onPress={jest.fn()} />);
    expect(screen.getByText('Grace Hopper')).toBeOnTheScreen();
    expect(screen.queryByText(/@/)).toBeNull();
    expect(screen.getByRole('button', { name: 'Grace Hopper' })).toBeOnTheScreen();
  });

  it('with neither name nor username falls back to "New member"', async () => {
    await render(<UserRow user={makeUser({ username: null, displayName: null })} />);
    expect(screen.getByText('New member')).toBeOnTheScreen();
    expect(screen.getByLabelText("New member's profile photo")).toBeOnTheScreen();
    expect(screen.queryByText(/null|@/)).toBeNull();
  });

  it('renders loading placeholders', async () => {
    await render(<UserRowSkeletons count={2} testID="sk" />);
    expect(screen.getByTestId('sk')).toBeOnTheScreen();
    expect(screen.getAllByTestId('skeleton', { includeHiddenElements: true })).toHaveLength(6);
  });
});

describe('FriendshipActionButton', () => {
  const user = makeUser();

  it('NONE: Add sends a request', async () => {
    await render(<FriendshipActionButton user={user} state="NONE" />);
    await fireEvent.press(screen.getByRole('button', { name: 'Add Grace Hopper as a friend' }));
    expect(send.mutate).toHaveBeenCalledWith('u2');
  });

  it('NONE: shows busy while sending', async () => {
    send.isPending = true;
    await render(<FriendshipActionButton user={user} state="NONE" />);
    expect(screen.getByRole('button', { name: 'Add Grace Hopper as a friend' })).toBeBusy();
  });

  it('REQUEST_RECEIVED: Accept accepts', async () => {
    await render(<FriendshipActionButton user={user} state="REQUEST_RECEIVED" />);
    await fireEvent.press(screen.getByRole('button', { name: 'Accept friend request from Grace Hopper' }));
    expect(accept.mutate).toHaveBeenCalledWith('u2');
  });

  it.each<[FriendshipState, string, string]>([
    ['REQUEST_SENT', 'Requested', 'Friend request sent to Grace Hopper'],
    ['FRIENDS', 'Friends', 'You and Grace Hopper are friends'],
    ['BLOCKED', 'Blocked', 'You blocked Grace Hopper'],
  ])('%s: a labelled, non-interactive status', async (state, text, label) => {
    await render(<FriendshipActionButton user={user} state={state} />);
    expect(screen.getByText(text)).toBeOnTheScreen();
    expect(screen.getByLabelText(label)).toBeOnTheScreen();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('labels people without a username by display name', async () => {
    await render(<FriendshipActionButton user={makeUser({ username: null, displayName: null })} state="NONE" />);
    expect(screen.getByRole('button', { name: 'Add New member as a friend' })).toBeOnTheScreen();
  });

  it('shows a failed action inline', async () => {
    send.isError = true;
    send.error = new ApiError(409, 'Unblock this person first.');
    await render(<FriendshipActionButton user={user} state="NONE" />);
    expect(screen.getByRole('alert')).toHaveTextContent('Unblock this person first.');
  });

  it('StatusPill renders its label', async () => {
    await render(<StatusPill label="Friends" icon="checkmark" accessibilityLabel="friends!" />);
    expect(screen.getByLabelText('friends!')).toHaveTextContent('Friends');
  });
});

describe('CloseFriendStar', () => {
  it('is a labelled switch that toggles', async () => {
    const onToggle = jest.fn();
    await render(<CloseFriendStar name="Grace" closeFriend={false} onToggle={onToggle} />);
    const sw = screen.getByRole('switch', { name: 'Close friend: Grace' });
    expect(sw).not.toBeChecked();
    expect(screen.getByTestId('icon-star-outline')).toBeOnTheScreen();
    await fireEvent.press(sw);
    expect(onToggle).toHaveBeenCalledWith(true);
  });

  it('shows the filled star when checked and toggles off', async () => {
    const onToggle = jest.fn();
    await render(<CloseFriendStar name="Grace" closeFriend onToggle={onToggle} />);
    expect(screen.getByRole('switch', { name: 'Close friend: Grace' })).toBeChecked();
    expect(screen.getByTestId('icon-star')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('switch'));
    expect(onToggle).toHaveBeenCalledWith(false);
  });

  it('does nothing when disabled', async () => {
    const onToggle = jest.fn();
    await render(<CloseFriendStar name="Grace" closeFriend={false} disabled onToggle={onToggle} />);
    expect(screen.getByRole('switch')).toBeDisabled();
    await fireEvent.press(screen.getByRole('switch'));
    expect(onToggle).not.toHaveBeenCalled();
  });
});

describe('SegmentedControl', () => {
  const segments = [
    { value: 'feed' as const, label: 'Feed' },
    { value: 'friends' as const, label: 'Friends', badge: 3 },
  ];

  it('is a labelled tab list with the selected tab marked', async () => {
    await render(<SegmentedControl label="Sections" segments={segments} value="feed" onChange={jest.fn()} />);
    expect(screen.getByLabelText('Sections')).toHaveProp('accessibilityRole', 'tablist');
    expect(screen.getByRole('tab', { name: 'Feed' })).toBeSelected();
    expect(screen.getByRole('tab', { name: 'Friends, 3 new' })).not.toBeSelected();
    expect(screen.getByTestId('segment-badge-friends')).toHaveTextContent('3');
  });

  it('reports a change only for a different segment', async () => {
    const onChange = jest.fn();
    await render(<SegmentedControl label="Sections" segments={segments} value="feed" onChange={onChange} />);
    await fireEvent.press(screen.getByRole('tab', { name: 'Feed' }));
    expect(onChange).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('tab', { name: 'Friends, 3 new' }));
    expect(onChange).toHaveBeenCalledWith('friends');
  });

  it('hides a zero badge and caps big ones at 9+', async () => {
    const { rerender } = await render(
      <SegmentedControl label="S" segments={[{ value: 'a', label: 'A', badge: 0 }]} value="a" onChange={jest.fn()} />,
    );
    expect(screen.queryByTestId('segment-badge-a')).toBeNull();
    expect(screen.getByRole('tab', { name: 'A' })).toBeOnTheScreen();
    await rerender(
      <SegmentedControl label="S" segments={[{ value: 'a', label: 'A', badge: 12 }]} value="a" onChange={jest.fn()} />,
    );
    expect(screen.getByTestId('segment-badge-a')).toHaveTextContent('9+');
  });
});

describe('Button size', () => {
  it('renders the compact variant with its label', async () => {
    const onPress = jest.fn();
    await render(<Button size="sm" title="Add" onPress={onPress} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Add' }));
    expect(onPress).toHaveBeenCalled();
  });
});

describe('InviteCard', () => {
  const invite = { token: 't', url: 'https://filmfuse.app/invite/t', expiresAt: '2026-10-30T00:00:00Z' };
  let create: ReturnType<typeof mutationResult>;
  beforeEach(() => {
    create = mutationResult({ mutateAsync: jest.fn(() => Promise.resolve(invite)) });
    mockCreate.mockImplementation(() => create);
  });

  it('creates an invite and shares its url', async () => {
    mockShare.mockResolvedValue('shared');
    await render(<InviteCard />);
    expect(screen.getByRole('header', { name: 'Invite friends' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Share invite link' }));
    await waitFor(() => expect(mockShare).toHaveBeenCalledWith(invite.url));
    expect(create.mutateAsync).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('invite-copied')).toBeNull();
  });

  it('confirms inline when the link was copied instead (web without Web Share)', async () => {
    mockShare.mockResolvedValue('copied');
    await render(<InviteCard />);
    await fireEvent.press(screen.getByRole('button', { name: 'Share invite link' }));
    expect(await screen.findByTestId('invite-copied')).toHaveTextContent(/Link copied/);
  });

  it('says nothing when the share sheet is dismissed', async () => {
    mockShare.mockResolvedValue('dismissed');
    await render(<InviteCard />);
    await fireEvent.press(screen.getByRole('button', { name: 'Share invite link' }));
    await waitFor(() => expect(mockShare).toHaveBeenCalled());
    expect(screen.queryByTestId('invite-copied')).toBeNull();
    expect(screen.queryByTestId('invite-error')).toBeNull();
  });

  it('shows an error if the invite cannot be created', async () => {
    create.mutateAsync.mockRejectedValue(new NetworkError());
    await render(<InviteCard />);
    await fireEvent.press(screen.getByRole('button', { name: 'Share invite link' }));
    expect(await screen.findByTestId('invite-error')).toHaveTextContent(/Couldn't reach FilmFuse/);
    expect(mockShare).not.toHaveBeenCalled();
  });

  it('shows an error if sharing throws', async () => {
    mockShare.mockRejectedValue(new Error('Clipboard unavailable'));
    await render(<InviteCard />);
    await fireEvent.press(screen.getByRole('button', { name: 'Share invite link' }));
    expect(await screen.findByTestId('invite-error')).toHaveTextContent('Clipboard unavailable');
  });
});

describe('inviteFailureCopy', () => {
  it.each([
    [404, "This invite isn't valid"],
    [410, 'This invite has expired'],
    [400, "That's your own invite"],
  ])('%p → %p', (status, title) => {
    expect(inviteFailureCopy(new ApiError(status, 'x'))?.title).toBe(title);
  });

  it('returns null for anything else', () => {
    expect(inviteFailureCopy(new ApiError(500, 'x'))).toBeNull();
    expect(inviteFailureCopy(new NetworkError())).toBeNull();
  });
});
