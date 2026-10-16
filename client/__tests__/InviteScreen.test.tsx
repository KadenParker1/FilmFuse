jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
let mockSession: unknown = { access_token: 't' };
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ session: mockSession }) }));
jest.mock('../api/invites', () => ({ useRedeemInvite: jest.fn() }));
jest.mock('../lib/pendingLink', () => ({
  setPendingLink: jest.fn(() => Promise.resolve()),
  getPendingLink: jest.fn(),
  clearPendingLink: jest.fn(),
}));

import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import InviteScreen from '../app/invite/[token]';
import { useRedeemInvite } from '../api/invites';
import { ApiError, NetworkError } from '../lib/apiClient';
import { setPendingLink } from '../lib/pendingLink';
import { makeUser } from '../test-utils/fixtures';
import { mutationResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const mockRedeem = useRedeemInvite as jest.Mock;
const mockSetPending = setPendingLink as jest.Mock;
let redeem: ReturnType<typeof mutationResult>;

beforeEach(() => {
  routerState.reset();
  routerState.params = { token: 'tok123' };
  mockSession = { access_token: 't' };
  redeem = mutationResult();
  mockRedeem.mockImplementation(() => redeem);
  mockSetPending.mockReset().mockResolvedValue(undefined);
});

describe('InviteScreen — signed in', () => {
  it('redeems the token exactly once while showing a loader', async () => {
    const { rerender } = await render(<InviteScreen />);
    expect(screen.getByLabelText('Accepting invite')).toBeOnTheScreen();
    await rerender(<InviteScreen />);
    expect(redeem.mutate).toHaveBeenCalledTimes(1);
    expect(redeem.mutate).toHaveBeenCalledWith('tok123');
    expect(mockSetPending).not.toHaveBeenCalled();
  });

  it('shows the new friend with a link to their profile', async () => {
    redeem.isSuccess = true;
    redeem.data = { inviter: makeUser({ id: 'u9', displayName: 'Nora Ephron', friendship: 'FRIENDS' }) };
    await render(<InviteScreen />);
    expect(screen.getByRole('header', { name: 'You and Nora Ephron are now friends' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: "View Nora Ephron's profile" }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/user/u9');
    await fireEvent.press(screen.getByRole('button', { name: 'Go to Social' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/');
  });

  it.each([
    [404, "This invite isn't valid"],
    [410, 'This invite has expired'],
    [400, "That's your own invite"],
  ])('%p shows a friendly message', async (status, title) => {
    redeem.isError = true;
    redeem.error = new ApiError(status, 'server text');
    await render(<InviteScreen />);
    expect(screen.getByText(title)).toBeOnTheScreen();
    expect(screen.queryByText('server text')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Go to Social' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/');
  });

  it('other failures can be retried', async () => {
    redeem.isError = true;
    redeem.error = new NetworkError();
    await render(<InviteScreen />);
    expect(screen.getByText("Couldn't accept this invite")).toBeOnTheScreen();
    redeem.mutate.mockClear();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(redeem.mutate).toHaveBeenCalledWith('tok123');
  });

  it('a missing token is invalid and never redeemed', async () => {
    routerState.params = {};
    await render(<InviteScreen />);
    expect(screen.getByText("This invite isn't valid")).toBeOnTheScreen();
    expect(redeem.mutate).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'Go to FilmFuse' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/');
  });
});

describe('InviteScreen — signed out', () => {
  beforeEach(() => {
    mockSession = null;
  });

  it('saves the invite link and sends you to sign in, without redeeming', async () => {
    await render(<InviteScreen />);
    expect(screen.getByLabelText('Saving your invite')).toBeOnTheScreen();
    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith('/sign-in'));
    expect(mockSetPending).toHaveBeenCalledWith('/invite/tok123');
    expect(mockSetPending.mock.invocationCallOrder[0]!).toBeLessThan(
      routerState.router.replace.mock.invocationCallOrder[0]!,
    );
    expect(redeem.mutate).not.toHaveBeenCalled();
  });

  it('still goes to sign in if the token cannot be saved', async () => {
    mockSetPending.mockRejectedValue(new Error('storage full'));
    await render(<InviteScreen />);
    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith('/sign-in'));
  });

  it('accepts an array param (first value)', async () => {
    routerState.params = { token: ['first', 'second'] };
    await render(<InviteScreen />);
    await waitFor(() => expect(mockSetPending).toHaveBeenCalledWith('/invite/first'));
  });
});
