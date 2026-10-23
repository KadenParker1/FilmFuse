jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/launchLink', () => ({ takeLaunchPath: jest.fn(), subscribeToLinks: jest.fn() }));
jest.mock('../lib/pendingLink', () => ({
  getPendingLink: jest.fn(),
  setPendingLink: jest.fn(() => Promise.resolve()),
  clearPendingLink: jest.fn(() => Promise.resolve()),
}));
jest.mock('../api/me', () => ({ useMe: jest.fn() }));
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ signOut: jest.fn() }) }));
jest.mock('expo-linking', () => ({ getInitialURL: jest.fn(), addEventListener: jest.fn() }));

import { render, waitFor } from '@testing-library/react-native';
import * as ExpoLinking from 'expo-linking';
import { useMe } from '../api/me';
import { GuardedLinkSaver } from '../components/GuardedLinkSaver';
import { ProfileGate } from '../components/ProfileGate';
import { PendingLinkRedeemer } from '../components/social/PendingLinkRedeemer';
import { subscribeToLinks, takeLaunchPath } from '../lib/launchLink';
import { clearPendingLink, getPendingLink, setPendingLink } from '../lib/pendingLink';
import { makeProfile } from '../test-utils/fixtures';
import { queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const SESSION = '/sessions/8f14e45f-ceea-4e7a-9b1c-2a3b4c5d6e7f';
const GROUP = '/groups/8f14e45f-ceea-4e7a-9b1c-2a3b4c5d6e7f';
const take = takeLaunchPath as jest.Mock;
const subscribe = subscribeToLinks as jest.Mock;
const save = setPendingLink as jest.Mock;
let unsubscribe: jest.Mock;
let onLink: ((path: string) => void) | null;

beforeEach(() => {
  jest.clearAllMocks();
  routerState.reset();
  onLink = null;
  unsubscribe = jest.fn();
  subscribe.mockImplementation((fn: (path: string) => void) => {
    onLink = fn;
    return unsubscribe;
  });
});

describe('GuardedLinkSaver', () => {
  it('signed out: remembers the session link the app was opened with', async () => {
    take.mockResolvedValue(SESSION);
    await render(<GuardedLinkSaver signedIn={false} />);
    await waitFor(() => expect(save).toHaveBeenCalledWith(SESSION));
  });

  it('signed out: remembers a group link too', async () => {
    take.mockResolvedValue(GROUP);
    await render(<GuardedLinkSaver signedIn={false} />);
    await waitFor(() => expect(save).toHaveBeenCalledWith(GROUP));
  });

  it.each(['/sessions/new', '/sessions/not-a-uuid', '/settings/blocked', '/user/u1', '/', null])(
    'ignores %p (not a guarded link)',
    async (path) => {
      take.mockResolvedValue(path);
      await render(<GuardedLinkSaver signedIn={false} />);
      await waitFor(() => expect(take).toHaveBeenCalled());
      expect(save).not.toHaveBeenCalled();
    },
  );

  it('signed in: reads the launch link once and drops it, and listens for nothing', async () => {
    take.mockResolvedValue(SESSION);
    await render(<GuardedLinkSaver signedIn />);
    await waitFor(() => expect(take).toHaveBeenCalledTimes(1));
    expect(save).not.toHaveBeenCalled();
    expect(subscribe).not.toHaveBeenCalled();
  });

  it('signed out: remembers guarded links that arrive while open; stops listening on sign-in', async () => {
    take.mockResolvedValue(null);
    const { rerender } = await render(<GuardedLinkSaver signedIn={false} />);
    onLink?.('/settings/blocked');
    onLink?.(GROUP);
    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith(GROUP);
    await rerender(<GuardedLinkSaver signedIn />);
    expect(unsubscribe).toHaveBeenCalled();
    expect(take).toHaveBeenCalledTimes(1);
  });

  it('survives storage failures', async () => {
    take.mockResolvedValue(SESSION);
    save.mockRejectedValueOnce(new Error('keychain locked'));
    await render(<GuardedLinkSaver signedIn={false} />);
    await waitFor(() => expect(save).toHaveBeenCalled());
  });
});

describe('a saved session link after sign-in', () => {
  const gate = () => (
    <ProfileGate>
      <PendingLinkRedeemer />
    </ProfileGate>
  );

  it('waits for onboarding, then opens the session once', async () => {
    (getPendingLink as jest.Mock).mockResolvedValue(SESSION);
    (useMe as jest.Mock).mockReturnValue(
      queryResult({ data: makeProfile({ onboardingCompletedAt: null, onboardingCompleted: false, onboardingStep: 2 }) }),
    );
    const { rerender } = await render(gate());
    expect(getPendingLink).not.toHaveBeenCalled();
    expect(routerState.router.push).not.toHaveBeenCalled();

    // Onboarding completes (the tabs layout renders its children).
    (useMe as jest.Mock).mockReturnValue(queryResult({ data: makeProfile() }));
    await rerender(gate());
    await waitFor(() => expect(routerState.router.push).toHaveBeenCalledWith(SESSION));
    expect(clearPendingLink).toHaveBeenCalledTimes(1);
  });

  it('opens it at once for an onboarded user', async () => {
    (getPendingLink as jest.Mock).mockResolvedValue(GROUP);
    (useMe as jest.Mock).mockReturnValue(queryResult({ data: makeProfile() }));
    await render(gate());
    await waitFor(() => expect(routerState.router.push).toHaveBeenCalledWith(GROUP));
  });
});

describe('launchLink', () => {
  it('web: the page URL is read once', async () => {
    const web = jest.requireActual<typeof import('../lib/launchLink')>('../lib/launchLink.ts');
    web.setLaunchUrlForTests(`https://filmfuse.app${SESSION}?utm=email`);
    await expect(web.takeLaunchPath()).resolves.toBe(SESSION);
    await expect(web.takeLaunchPath()).resolves.toBeNull();
    expect(typeof web.subscribeToLinks(() => {})).toBe('function');
  });

  it('native: the initial URL is read once; arriving links are parsed', async () => {
    const native = jest.requireActual<typeof import('../lib/launchLink.native')>('../lib/launchLink.native');
    (ExpoLinking.getInitialURL as jest.Mock).mockResolvedValue(`filmfuse:/${SESSION}`);
    native.setLaunchUrlForTests();
    await expect(native.takeLaunchPath()).resolves.toBe(SESSION);
    await expect(native.takeLaunchPath()).resolves.toBeNull();

    const remove = jest.fn();
    let handler: ((e: { url: string }) => void) | undefined;
    (ExpoLinking.addEventListener as jest.Mock).mockImplementation((_t: string, h: (e: { url: string }) => void) => {
      handler = h;
      return { remove };
    });
    const got = jest.fn();
    const stop = native.subscribeToLinks(got);
    handler?.({ url: `https://filmfuse.app${GROUP}` });
    expect(got).toHaveBeenCalledWith(GROUP);
    stop();
    expect(remove).toHaveBeenCalled();
  });
});
