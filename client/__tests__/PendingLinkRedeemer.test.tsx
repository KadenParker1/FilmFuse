jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/pendingLink', () => ({
  getPendingLink: jest.fn(),
  clearPendingLink: jest.fn(() => Promise.resolve()),
  setPendingLink: jest.fn(),
}));

import { render, screen, waitFor } from '@testing-library/react-native';
import { PendingLinkRedeemer } from '../components/social/PendingLinkRedeemer';
import { clearPendingLink, getPendingLink } from '../lib/pendingLink';
import { routerState } from '../test-utils/router';

const getPending = getPendingLink as jest.Mock;
const clearPending = clearPendingLink as jest.Mock;

beforeEach(() => {
  routerState.reset();
  getPending.mockReset();
  clearPending.mockClear();
});

describe('PendingLinkRedeemer', () => {
  it('clears a saved invite link, then opens it once', async () => {
    getPending.mockResolvedValue('/invite/a%20b');
    await render(<PendingLinkRedeemer />);
    await waitFor(() => expect(routerState.router.push).toHaveBeenCalledWith('/invite/a%20b'));
    expect(clearPending).toHaveBeenCalledTimes(1);
    expect(clearPending.mock.invocationCallOrder[0]!).toBeLessThan(
      routerState.router.push.mock.invocationCallOrder[0]!,
    );
    await screen.rerender(<PendingLinkRedeemer />);
    expect(getPending).toHaveBeenCalledTimes(1);
    expect(routerState.router.push).toHaveBeenCalledTimes(1);
  });

  it('reopens a saved profile link', async () => {
    getPending.mockResolvedValue('/user/u9');
    await render(<PendingLinkRedeemer />);
    await waitFor(() => expect(routerState.router.push).toHaveBeenCalledWith('/user/u9'));
  });

  it('reopens a saved rating link (from a comment email)', async () => {
    getPending.mockResolvedValue('/ratings/r9');
    await render(<PendingLinkRedeemer />);
    await waitFor(() => expect(routerState.router.push).toHaveBeenCalledWith('/ratings/r9'));
  });

  it('does nothing without a saved link', async () => {
    getPending.mockResolvedValue(null);
    await render(<PendingLinkRedeemer />);
    await waitFor(() => expect(getPending).toHaveBeenCalled());
    expect(clearPending).not.toHaveBeenCalled();
    expect(routerState.router.push).not.toHaveBeenCalled();
  });

  it('survives unreadable storage', async () => {
    getPending.mockRejectedValue(new Error('keychain locked'));
    await render(<PendingLinkRedeemer />);
    await waitFor(() => expect(getPending).toHaveBeenCalled());
    expect(routerState.router.push).not.toHaveBeenCalled();
  });

  it('waits for navigation to be ready', async () => {
    getPending.mockResolvedValue('/invite/tok');
    routerState.navReady = false;
    const { rerender } = await render(<PendingLinkRedeemer />);
    expect(getPending).not.toHaveBeenCalled();
    routerState.navReady = true;
    await rerender(<PendingLinkRedeemer />);
    await waitFor(() => expect(routerState.router.push).toHaveBeenCalledWith('/invite/tok'));
  });
});
