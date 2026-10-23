jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/me', () => ({ useMe: jest.fn() }));
let mockSession: unknown = { access_token: 't' };
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ session: mockSession }) }));
jest.mock('../lib/pendingLink', () => ({ setPendingLink: jest.fn(() => Promise.resolve()) }));
jest.mock('../api/users', () => ({ usePublicProfile: jest.fn() }));
jest.mock('../api/reports', () => ({ ...jest.requireActual('../api/reports'), useCreateReport: jest.fn() }));
jest.mock('../api/feed', () => ({ useUserRatings: jest.fn(), useLikeRating: jest.fn(), useUnlikeRating: jest.fn() }));
const mockOpenQuickRate = jest.fn();
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openQuickRate: mockOpenQuickRate, openRatePicker: jest.fn(), showTasteUpdating: jest.fn() }),
}));
jest.mock('../api/social', () => ({
  useAcceptRequest: jest.fn(),
  useBlock: jest.fn(),
  useCancelRequest: jest.fn(),
  useDeclineRequest: jest.fn(),
  useSendFriendRequest: jest.fn(),
  useSetCloseFriend: jest.fn(),
  useUnblock: jest.fn(),
  useUnfriend: jest.fn(),
}));

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import UserProfileScreen from '../app/user/[id]';
import { useLikeRating, useUnlikeRating, useUserRatings } from '../api/feed';
import { useMe } from '../api/me';
import {
  useAcceptRequest,
  useBlock,
  useCancelRequest,
  useDeclineRequest,
  useSendFriendRequest,
  useSetCloseFriend,
  useUnblock,
  useUnfriend,
} from '../api/social';
import { usePublicProfile } from '../api/users';
import { useCreateReport } from '../api/reports';
import { ApiError, NetworkError } from '../lib/apiClient';
import { setPendingLink } from '../lib/pendingLink';
import { makeFeedItem, makeMovie, makeProfile, makePublicProfile } from '../test-utils/fixtures';
import { infiniteResult, mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';
import type { FriendshipState } from '../types/api';

const hooks = {
  accept: useAcceptRequest as jest.Mock,
  block: useBlock as jest.Mock,
  cancel: useCancelRequest as jest.Mock,
  decline: useDeclineRequest as jest.Mock,
  send: useSendFriendRequest as jest.Mock,
  setClose: useSetCloseFriend as jest.Mock,
  unblock: useUnblock as jest.Mock,
  unfriend: useUnfriend as jest.Mock,
};
type Key = keyof typeof hooks;
let mut: Record<Key, ReturnType<typeof mutationResult>>;
const mockProfile = usePublicProfile as jest.Mock;
const mockUserRatings = useUserRatings as jest.Mock;

const showProfile = (friendship: FriendshipState, overrides = {}) =>
  mockProfile.mockReturnValue(queryResult({ data: makePublicProfile({ friendship, ...overrides }) }));

beforeEach(() => {
  mockSession = { access_token: 't' };
  routerState.reset();
  routerState.params = { id: 'u2' };
  (useMe as jest.Mock).mockReturnValue(queryResult({ data: makeProfile({ id: 'u1' }) }));
  mut = Object.fromEntries((Object.keys(hooks) as Key[]).map((k) => [k, mutationResult()])) as typeof mut;
  (Object.keys(hooks) as Key[]).forEach((k) => hooks[k].mockImplementation(() => mut[k]));
  mockUserRatings.mockReturnValue(infiniteResult({ items: [] }));
  (useLikeRating as jest.Mock).mockReturnValue(mutationResult());
  (useUnlikeRating as jest.Mock).mockReturnValue(mutationResult());
  mockOpenQuickRate.mockClear();
  report = mutationResult();
  (useCreateReport as jest.Mock).mockImplementation(() => report);
});

let report: ReturnType<typeof mutationResult>;

/** Runs the mutate's onSuccess option, as TanStack would after the request succeeds. */
const succeed = async (m: ReturnType<typeof mutationResult>) => {
  const opts = m.mutate.mock.calls.at(-1)?.[1] as { onSuccess?: () => void } | undefined;
  await act(async () => {
    opts?.onSuccess?.();
  });
};

describe('UserProfileScreen — common', () => {
  it('shows avatar, name, @username, bio and counts', async () => {
    showProfile('NONE');
    await render(<UserProfileScreen />);
    expect(mockProfile).toHaveBeenCalledWith('u2');
    expect(screen.getByRole('header', { name: 'Grace Hopper' })).toBeOnTheScreen();
    expect(screen.getByText('@grace')).toBeOnTheScreen();
    expect(screen.getByTestId('user-bio')).toHaveTextContent('Debugging since 1947.');
    expect(screen.getByTestId('friend-count')).toHaveTextContent('12 friends');
    expect(screen.getByTestId('mutual-count')).toHaveTextContent('2 mutual friends');
    expect(screen.getByLabelText("Grace Hopper's profile photo")).toBeOnTheScreen();
  });

  it('uses singulars and hides an empty bio', async () => {
    showProfile('NONE', { bio: null, friendCount: 1, mutualFriendCount: 1, displayName: null });
    await render(<UserProfileScreen />);
    expect(screen.getByRole('header', { name: '@grace' })).toBeOnTheScreen();
    expect(screen.queryByTestId('user-bio')).toBeNull();
    expect(screen.getByTestId('friend-count')).toHaveTextContent('1 friend');
    expect(screen.getByTestId('mutual-count')).toHaveTextContent('1 mutual friend');
  });

  it('handles someone without a username yet (no "@null")', async () => {
    showProfile('NONE', { username: null });
    await render(<UserProfileScreen />);
    expect(screen.getByRole('header', { name: 'Grace Hopper' })).toBeOnTheScreen();
    expect(screen.queryByText(/@/)).toBeNull();
    showProfile('NONE', { username: null, displayName: null });
    await screen.rerender(<UserProfileScreen />);
    expect(screen.getByRole('header', { name: 'New member' })).toBeOnTheScreen();
    expect(screen.getByLabelText("New member's profile photo")).toBeOnTheScreen();
    expect(screen.queryByText(/null/)).toBeNull();
  });

  it('a cold, signed-out load (email link) saves the profile and goes to sign in', async () => {
    mockSession = null;
    mockProfile.mockReset().mockReturnValue(queryResult());
    await render(<UserProfileScreen />);
    expect(screen.getByLabelText('Sign in to see this profile')).toBeOnTheScreen();
    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith('/sign-in'));
    expect(setPendingLink).toHaveBeenCalledWith('/user/u2');
    expect(mockProfile).not.toHaveBeenCalled();
  });

  it('shows a skeleton while loading', async () => {
    mockProfile.mockReturnValue(queryResult());
    await render(<UserProfileScreen />);
    expect(screen.getByTestId('user-profile-loading')).toBeOnTheScreen();
  });

  it('404 → "This profile isn\'t available"', async () => {
    mockProfile.mockReturnValue(
      queryResult({ isError: true, isPending: false, error: new ApiError(404, 'Not found') }),
    );
    await render(<UserProfileScreen />);
    expect(screen.getByText("This profile isn't available")).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  });

  it('a missing id is treated as unavailable', async () => {
    routerState.params = {};
    mockProfile.mockReturnValue(queryResult());
    await render(<UserProfileScreen />);
    expect(screen.getByText("This profile isn't available")).toBeOnTheScreen();
  });

  it('other errors offer a retry', async () => {
    const refetch = jest.fn();
    mockProfile.mockReturnValue(queryResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await render(<UserProfileScreen />);
    expect(screen.getByText("Couldn't load this profile")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('back goes back, or home after a deep link', async () => {
    showProfile('NONE');
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.back).toHaveBeenCalled();
    routerState.router.canGoBack.mockReturnValue(false);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/');
  });

  it('your own profile offers Edit instead of friendship actions', async () => {
    (useMe as jest.Mock).mockReturnValue(queryResult({ data: makeProfile({ id: 'u2' }) }));
    showProfile('NONE');
    await render(<UserProfileScreen />);
    expect(screen.queryByRole('button', { name: 'Add friend' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'More options' })).toBeNull();
    expect(screen.queryByTestId('mutual-count')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Edit your profile' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/profile/edit');
  });
});

describe('UserProfileScreen — NONE', () => {
  it('"Add friend" sends a request', async () => {
    showProfile('NONE');
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Add friend' }));
    expect(mut.send.mutate).toHaveBeenCalledWith('u2');
  });

  it('shows sending progress and errors', async () => {
    showProfile('NONE');
    mut.send.isPending = true;
    const { rerender } = await render(<UserProfileScreen />);
    expect(screen.getByRole('button', { name: 'Add friend' })).toBeBusy();
    mut.send.isPending = false;
    mut.send.isError = true;
    mut.send.error = new ApiError(409, 'Unblock this person first.');
    await rerender(<UserProfileScreen />);
    expect(screen.getByTestId('profile-action-error')).toHaveTextContent('Unblock this person first.');
  });

  it('overflow menu → Block → confirm', async () => {
    showProfile('NONE');
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'More options' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Block Grace Hopper' }));
    expect(screen.getByText('Block Grace Hopper?')).toBeOnTheScreen();
    expect(mut.block.mutate).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'Block' }));
    expect(mut.block.mutate).toHaveBeenCalledWith('u2', expect.any(Object));
    await succeed(mut.block);
    await screen.rerender(<UserProfileScreen />);
    expect(screen.queryByText('Block Grace Hopper?')).toBeNull();
  });

  it('cancelling the block confirm closes the sheet without blocking', async () => {
    showProfile('NONE');
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'More options' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Block Grace Hopper' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByText('Block Grace Hopper?')).toBeNull();
    expect(mut.block.mutate).not.toHaveBeenCalled();
    expect(mut.block.reset).toHaveBeenCalled();
  });

  it('shows a block failure inside the confirm sheet', async () => {
    showProfile('NONE');
    mut.block.isError = true;
    mut.block.error = new NetworkError();
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'More options' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Block Grace Hopper' }));
    expect(screen.getByTestId('profile-action-error')).toHaveTextContent(/Couldn't reach/);
  });

  it('the overflow menu can be dismissed', async () => {
    showProfile('NONE');
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'More options' }));
    expect(screen.getByRole('button', { name: 'Block Grace Hopper' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('button', { name: 'Block Grace Hopper' })).toBeNull();
  });
});

describe('UserProfileScreen — REQUEST_SENT', () => {
  it('"Requested" asks before cancelling', async () => {
    showProfile('REQUEST_SENT');
    await render(<UserProfileScreen />);
    const requested = screen.getByRole('button', { name: 'Requested' });
    expect(requested).toHaveProp('accessibilityHint', 'Cancels your friend request');
    await fireEvent.press(requested);
    expect(screen.getByText('Cancel friend request?')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel request' }));
    expect(mut.cancel.mutate).toHaveBeenCalledWith('u2', expect.any(Object));
    await succeed(mut.cancel);
    await screen.rerender(<UserProfileScreen />);
    expect(screen.queryByText('Cancel friend request?')).toBeNull();
  });

  it('"Keep request" closes without cancelling', async () => {
    showProfile('REQUEST_SENT');
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Requested' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Keep request' }));
    expect(mut.cancel.mutate).not.toHaveBeenCalled();
    expect(screen.queryByText('Cancel friend request?')).toBeNull();
  });

  it('has the overflow menu', async () => {
    showProfile('REQUEST_SENT');
    await render(<UserProfileScreen />);
    expect(screen.getByRole('button', { name: 'More options' })).toBeOnTheScreen();
  });
});

describe('UserProfileScreen — REQUEST_RECEIVED', () => {
  it('Accept and Decline', async () => {
    showProfile('REQUEST_RECEIVED');
    await render(<UserProfileScreen />);
    expect(screen.getByText('Grace Hopper sent you a friend request.')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Accept' }));
    expect(mut.accept.mutate).toHaveBeenCalledWith('u2');
    await fireEvent.press(screen.getByRole('button', { name: 'Decline' }));
    expect(mut.decline.mutate).toHaveBeenCalledWith('u2');
    expect(screen.getByRole('button', { name: 'More options' })).toBeOnTheScreen();
  });

  it('disables the other button while one is pending', async () => {
    showProfile('REQUEST_RECEIVED');
    mut.accept.isPending = true;
    await render(<UserProfileScreen />);
    expect(screen.getByRole('button', { name: 'Accept' })).toBeBusy();
    expect(screen.getByRole('button', { name: 'Decline' })).toBeDisabled();
  });

  it('shows an accept failure', async () => {
    showProfile('REQUEST_RECEIVED');
    mut.accept.isError = true;
    mut.accept.error = new ApiError(404, 'No pending request');
    await render(<UserProfileScreen />);
    expect(screen.getByTestId('profile-action-error')).toHaveTextContent('No pending request');
  });
});

describe('UserProfileScreen — FRIENDS', () => {
  const openSheet = async () => {
    await fireEvent.press(screen.getByRole('button', { name: 'Friends' }));
  };

  it('"Friends ✓" opens the friendship sheet; no overflow menu', async () => {
    showProfile('FRIENDS');
    await render(<UserProfileScreen />);
    expect(screen.getByText('Friends ✓')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'More options' })).toBeNull();
    await openSheet();
    expect(screen.getByText('You and Grace Hopper')).toBeOnTheScreen();
    expect(screen.getByRole('switch', { name: 'Close friend' })).not.toBeChecked();
  });

  it('"Movie night with…" starts a Tonight session with them preselected', async () => {
    showProfile('FRIENDS');
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Movie night with Grace Hopper' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/sessions/new?memberIds=u2');
  });

  it.each(['NONE', 'REQUEST_SENT', 'REQUEST_RECEIVED', 'BLOCKED'] as const)('no movie night for %s', async (state) => {
    showProfile(state);
    await render(<UserProfileScreen />);
    expect(screen.queryByRole('button', { name: /^Movie night with/ })).toBeNull();
  });

  it('toggles close friend', async () => {
    showProfile('FRIENDS');
    await render(<UserProfileScreen />);
    await openSheet();
    await fireEvent(screen.getByRole('switch', { name: 'Close friend' }), 'valueChange', true);
    expect(mut.setClose.mutate).toHaveBeenCalledWith({ userId: 'u2', closeFriend: true });
  });

  it('reads the close-friend flag straight from the profile', async () => {
    showProfile('FRIENDS', { closeFriend: true });
    const { rerender } = await render(<UserProfileScreen />);
    await openSheet();
    expect(screen.getByRole('switch', { name: 'Close friend' })).toBeChecked();
    expect(screen.getByRole('switch', { name: 'Close friend' })).toBeEnabled();
    // The optimistic cache update re-renders with the new flag.
    showProfile('FRIENDS', { closeFriend: false });
    await rerender(<UserProfileScreen />);
    expect(screen.getByRole('switch', { name: 'Close friend' })).not.toBeChecked();
  });

  it('unstars a close friend and disables the switch while saving', async () => {
    showProfile('FRIENDS', { closeFriend: true });
    const { rerender } = await render(<UserProfileScreen />);
    await openSheet();
    await fireEvent(screen.getByRole('switch', { name: 'Close friend' }), 'valueChange', false);
    expect(mut.setClose.mutate).toHaveBeenCalledWith({ userId: 'u2', closeFriend: false });
    mut.setClose.isPending = true;
    await rerender(<UserProfileScreen />);
    expect(screen.getByRole('switch', { name: 'Close friend' })).toBeDisabled();
  });

  it('shows a close-friend failure', async () => {
    showProfile('FRIENDS');
    mut.setClose.isError = true;
    mut.setClose.error = new ApiError(404, 'Not friends');
    await render(<UserProfileScreen />);
    await openSheet();
    expect(screen.getByTestId('profile-action-error')).toHaveTextContent('Not friends');
  });

  it('Unfriend asks for confirmation', async () => {
    showProfile('FRIENDS');
    await render(<UserProfileScreen />);
    await openSheet();
    await fireEvent.press(screen.getByRole('button', { name: 'Unfriend' }));
    expect(screen.getByText('Unfriend Grace Hopper?')).toBeOnTheScreen();
    expect(mut.unfriend.mutate).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'Unfriend' }));
    expect(mut.unfriend.mutate).toHaveBeenCalledWith('u2', expect.any(Object));
  });

  it('Unfriend can be called off', async () => {
    showProfile('FRIENDS');
    await render(<UserProfileScreen />);
    await openSheet();
    await fireEvent.press(screen.getByRole('button', { name: 'Unfriend' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(mut.unfriend.mutate).not.toHaveBeenCalled();
    expect(screen.queryByText('Unfriend Grace Hopper?')).toBeNull();
  });

  it('shows an unfriend failure in the sheet', async () => {
    showProfile('FRIENDS');
    mut.unfriend.isError = true;
    mut.unfriend.error = new NetworkError();
    await render(<UserProfileScreen />);
    await openSheet();
    await fireEvent.press(screen.getByRole('button', { name: 'Unfriend' }));
    expect(screen.getByTestId('profile-action-error')).toBeOnTheScreen();
  });

  it('Block from the sheet confirms first', async () => {
    showProfile('FRIENDS');
    await render(<UserProfileScreen />);
    await openSheet();
    await fireEvent.press(screen.getByRole('button', { name: 'Block' }));
    expect(screen.getByText('Block Grace Hopper?')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Block' }));
    expect(mut.block.mutate).toHaveBeenCalledWith('u2', expect.any(Object));
  });

  it('cannot be dismissed mid-request', async () => {
    showProfile('FRIENDS');
    mut.unfriend.isPending = true;
    await render(<UserProfileScreen />);
    await openSheet();
    await fireEvent.press(screen.getByRole('button', { name: 'Unfriend' }));
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByText('Unfriend Grace Hopper?')).toBeOnTheScreen();
  });

  it('Done closes the sheet', async () => {
    showProfile('FRIENDS');
    await render(<UserProfileScreen />);
    await openSheet();
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByText('You and Grace Hopper')).toBeNull();
  });
});

describe('UserProfileScreen — BLOCKED', () => {
  it('shows Unblock and no overflow menu', async () => {
    showProfile('BLOCKED');
    await render(<UserProfileScreen />);
    expect(screen.getByText(/You've blocked Grace Hopper/)).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'More options' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add friend' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Unblock' }));
    expect(mut.unblock.mutate).toHaveBeenCalledWith('u2');
  });

  it('shows an unblock failure', async () => {
    showProfile('BLOCKED');
    mut.unblock.isError = true;
    mut.unblock.error = new NetworkError();
    await render(<UserProfileScreen />);
    expect(screen.getByTestId('profile-action-error')).toBeOnTheScreen();
  });
});

describe('UserProfileScreen — ratings section', () => {
  const items = [1, 2, 3, 4, 5, 6].map((n) =>
    makeFeedItem({ id: `f${n}`, movie: makeMovie({ id: 100 + n, title: `Movie ${n}` }), score: n + 4 }),
  );
  const section = () => within(screen.getByTestId('user-ratings'));

  it("previews this person's first 5 ratings without repeating their name on every card", async () => {
    showProfile('FRIENDS');
    mockUserRatings.mockReturnValue(infiniteResult({ items }));
    await render(<UserProfileScreen />);
    expect(mockUserRatings).toHaveBeenCalledWith('u2');
    expect(section().getByRole('header', { name: 'Ratings' })).toBeOnTheScreen();
    expect(screen.getByTestId('feed-card-f5')).toBeOnTheScreen();
    expect(screen.queryByTestId('feed-card-f6')).toBeNull();
    expect(section().queryByRole('button', { name: /^Grace Hopper/ })).toBeNull();
    await fireEvent.press(section().getByRole('button', { name: 'Movie 1, 1995' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/101');
  });

  it('"See all" opens their full ratings list', async () => {
    showProfile('NONE');
    mockUserRatings.mockReturnValue(infiniteResult({ items }));
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: "See all of Grace Hopper's ratings" }));
    expect(routerState.router.push).toHaveBeenCalledWith('/user/u2/ratings');
  });

  it('no "See all" when everything fits', async () => {
    showProfile('NONE');
    mockUserRatings.mockReturnValue(infiniteResult({ items: items.slice(0, 2) }));
    await render(<UserProfileScreen />);
    expect(screen.queryByRole('button', { name: /See all/ })).toBeNull();
  });

  it('empty, loading and error states', async () => {
    showProfile('NONE');
    const { rerender } = await render(<UserProfileScreen />);
    expect(screen.getByTestId('user-ratings-empty')).toHaveTextContent("Grace Hopper hasn't shared any ratings with you yet.");

    mockUserRatings.mockReturnValue(infiniteResult());
    await rerender(<UserProfileScreen />);
    expect(screen.getByTestId('user-ratings-loading')).toBeOnTheScreen();

    const refetch = jest.fn();
    mockUserRatings.mockReturnValue(infiniteResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await rerender(<UserProfileScreen />);
    await fireEvent.press(section().getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('is hidden for someone you blocked', async () => {
    showProfile('BLOCKED');
    await render(<UserProfileScreen />);
    expect(screen.queryByTestId('user-ratings')).toBeNull();
  });

  it('on your own profile: own wording, visibility and Edit on each card', async () => {
    (useMe as jest.Mock).mockReturnValue(queryResult({ data: makeProfile({ id: 'u2' }) }));
    showProfile('NONE');
    mockUserRatings.mockReturnValue(
      infiniteResult({ items: [makeFeedItem({ id: 'mine', mine: true, visibility: 'PRIVATE', movie: makeMovie({ title: 'Heat' }) })] }),
    );
    await render(<UserProfileScreen />);
    expect(screen.getByLabelText('Visible to: Only you')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Edit your rating of Heat' }));
    expect(mockOpenQuickRate).toHaveBeenCalledWith(expect.objectContaining({ title: 'Heat' }));

    mockUserRatings.mockReturnValue(infiniteResult({ items: [] }));
    await render(<UserProfileScreen />);
    expect(screen.getByTestId('user-ratings-empty')).toHaveTextContent("You haven't rated anything yet.");
  });
});

describe('UserProfileScreen — reporting', () => {
  it('overflow menu → Report → reason → sends a USER report; then a thank-you and Done', async () => {
    showProfile('NONE');
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'More options' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Report Grace Hopper' }));
    expect(screen.getByRole('header', { name: 'Report this person' })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Send report' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('radio', { name: 'Harassment' }));
    await fireEvent.changeText(screen.getByLabelText('Details (optional)'), 'Keeps messaging me');
    await fireEvent.press(screen.getByRole('button', { name: 'Send report' }));
    expect(report.mutate).toHaveBeenCalledWith({
      targetType: 'USER',
      targetId: 'u2',
      reason: 'HARASSMENT',
      details: 'Keeps messaging me',
    });
    report.isSuccess = true;
    await screen.rerender(<UserProfileScreen />);
    expect(screen.getByText('Thanks for letting us know')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByText('Thanks for letting us know')).toBeNull();
  });

  it('friends can be reported from the friendship sheet', async () => {
    showProfile('FRIENDS');
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Friends' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Report Grace Hopper' }));
    expect(screen.getByTestId('report-form')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByTestId('report-form')).toBeNull();
  });

  it('shows a report failure inline', async () => {
    showProfile('NONE');
    report.isError = true;
    report.error = new ApiError(400, "You can't report yourself");
    await render(<UserProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'More options' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Report Grace Hopper' }));
    expect(screen.getByRole('alert')).toHaveTextContent("You can't report yourself");
  });
});
