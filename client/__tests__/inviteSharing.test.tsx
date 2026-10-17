jest.mock('expo-clipboard', () => ({ setStringAsync: jest.fn(() => Promise.resolve(true)) }));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(() => Promise.resolve()),
  deleteItemAsync: jest.fn(() => Promise.resolve()),
}));

import { Platform, Share } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import * as SecureStore from 'expo-secure-store';
import * as nativeShare from '../components/social/shareInvite.native';
import { INVITE_MESSAGE } from '../components/social/shareInviteTypes';
import * as nativePending from '../lib/pendingLink.native';
import { invitePath, isPendingLinkPath, profilePath, ratingPath } from '../lib/pendingLinkPath';

// jest resolves platform files to .native (ios preset); load the web ones explicitly.
const webShare = jest.requireActual<typeof import('../components/social/shareInvite')>(
  '../components/social/shareInvite.ts',
);
const webPending = jest.requireActual<typeof import('../lib/pendingLink')>('../lib/pendingLink.ts');

const URL = 'https://filmfuse.app/invite/abc';
const setString = Clipboard.setStringAsync as jest.Mock;

describe('shareInvite (native)', () => {
  const originalOS = Platform.OS;
  let shareSpy: jest.SpyInstance;
  beforeEach(() => {
    shareSpy = jest.spyOn(Share, 'share').mockResolvedValue({ action: Share.sharedAction, activityType: undefined });
  });
  afterEach(() => {
    shareSpy.mockRestore();
    Object.defineProperty(Platform, 'OS', { value: originalOS, configurable: true });
  });

  it('iOS passes the link as url beside the message', async () => {
    Object.defineProperty(Platform, 'OS', { value: 'ios', configurable: true });
    await expect(nativeShare.shareInvite(URL)).resolves.toBe('shared');
    expect(shareSpy).toHaveBeenCalledWith({ message: INVITE_MESSAGE, url: URL }, expect.any(Object));
  });

  it('Android puts the link in the message', async () => {
    Object.defineProperty(Platform, 'OS', { value: 'android', configurable: true });
    await expect(nativeShare.shareInvite(URL)).resolves.toBe('shared');
    expect(shareSpy).toHaveBeenCalledWith({ message: `${INVITE_MESSAGE} ${URL}` }, expect.any(Object));
  });

  it('reports a dismissed sheet', async () => {
    shareSpy.mockResolvedValue({ action: Share.dismissedAction, activityType: undefined });
    await expect(nativeShare.shareInvite(URL)).resolves.toBe('dismissed');
    expect(setString).not.toHaveBeenCalled();
  });
});

describe('shareInvite (web)', () => {
  const nav = global.navigator as Navigator & { share?: unknown };
  const originalShare = nav.share;
  beforeEach(() => setString.mockClear());
  afterEach(() => {
    Object.defineProperty(nav, 'share', { value: originalShare, configurable: true, writable: true });
  });
  const withShare = (impl: unknown) =>
    Object.defineProperty(nav, 'share', { value: impl, configurable: true, writable: true });

  it('uses the Web Share API when available', async () => {
    const share = jest.fn(() => Promise.resolve());
    withShare(share);
    await expect(webShare.shareInvite(URL)).resolves.toBe('shared');
    expect(share).toHaveBeenCalledWith({ title: 'Join me on FilmFuse', text: INVITE_MESSAGE, url: URL });
    expect(setString).not.toHaveBeenCalled();
  });

  it('treats an AbortError as the user closing the sheet', async () => {
    withShare(jest.fn(() => Promise.reject(Object.assign(new Error('cancel'), { name: 'AbortError' }))));
    await expect(webShare.shareInvite(URL)).resolves.toBe('dismissed');
    expect(setString).not.toHaveBeenCalled();
  });

  it('falls back to the clipboard when Web Share fails otherwise', async () => {
    withShare(jest.fn(() => Promise.reject(Object.assign(new Error('nope'), { name: 'NotAllowedError' }))));
    await expect(webShare.shareInvite(URL)).resolves.toBe('copied');
    expect(setString).toHaveBeenCalledWith(URL);
  });

  it('copies to the clipboard when there is no Web Share API', async () => {
    withShare(undefined);
    await expect(webShare.shareInvite(URL)).resolves.toBe('copied');
    expect(setString).toHaveBeenCalledWith(URL);
  });
});

describe('pendingLinkPath', () => {
  it('builds encoded invite and profile paths', () => {
    expect(invitePath('a b')).toBe('/invite/a%20b');
    expect(profilePath('u/1')).toBe('/user/u%2F1');
    expect(ratingPath('r 1')).toBe('/ratings/r%201');
  });

  it.each([
    ['/invite/abc', true],
    ['/user/u1', true],
    ['/ratings/r1', true],
    ['/ratings/', false],
    ['/ratings/r1/edit', false],
    ['/user/', false],
    ['/settings/blocked', false],
    ['https://evil.example/user/1', false],
    ['/user/1/../../x', false],
    [null, false],
  ])('isPendingLinkPath(%p) → %p', (p, ok) => expect(isPendingLinkPath(p)).toBe(ok));
});

describe('pendingLink (native, SecureStore)', () => {
  beforeEach(() => jest.clearAllMocks());

  it('gets, sets and clears under one key', async () => {
    (SecureStore.getItemAsync as jest.Mock).mockResolvedValue('/invite/tok');
    await expect(nativePending.getPendingLink()).resolves.toBe('/invite/tok');
    await nativePending.setPendingLink('/user/u2');
    await nativePending.clearPendingLink();
    expect(SecureStore.getItemAsync).toHaveBeenCalledWith(nativePending.PENDING_LINK_KEY);
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith(nativePending.PENDING_LINK_KEY, '/user/u2');
    expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith(nativePending.PENDING_LINK_KEY);
    expect(nativePending.PENDING_LINK_KEY).toMatch(/^[A-Za-z0-9._-]+$/);
  });

  it('ignores anything that is not an invite or profile path', async () => {
    (SecureStore.getItemAsync as jest.Mock).mockResolvedValue('/settings/blocked');
    await expect(nativePending.getPendingLink()).resolves.toBeNull();
    await nativePending.setPendingLink('https://evil.example');
    expect(SecureStore.setItemAsync).not.toHaveBeenCalled();
  });
});

describe('pendingLink (web, localStorage)', () => {
  const g = global as unknown as { window?: { localStorage?: Storage } };
  const originalWindow = g.window;
  afterEach(() => {
    g.window = originalWindow;
  });

  it('round-trips through localStorage and rejects other paths', async () => {
    const store = new Map<string, string>();
    g.window = {
      localStorage: {
        getItem: (k: string) => store.get(k) ?? null,
        setItem: (k: string, v: string) => void store.set(k, v),
        removeItem: (k: string) => void store.delete(k),
      } as Storage,
    };
    await expect(webPending.getPendingLink()).resolves.toBeNull();
    await webPending.setPendingLink('/invite/tok');
    expect(store.get(webPending.PENDING_LINK_KEY)).toBe('/invite/tok');
    await expect(webPending.getPendingLink()).resolves.toBe('/invite/tok');
    await webPending.clearPendingLink();
    await expect(webPending.getPendingLink()).resolves.toBeNull();
    await webPending.setPendingLink('/elsewhere');
    expect(store.size).toBe(0);
    store.set(webPending.PENDING_LINK_KEY, 'javascript:alert(1)');
    await expect(webPending.getPendingLink()).resolves.toBeNull();
  });

  it('is a harmless no-op without localStorage (static render)', async () => {
    g.window = undefined;
    await expect(webPending.getPendingLink()).resolves.toBeNull();
    await expect(webPending.setPendingLink('/invite/x')).resolves.toBeUndefined();
    await expect(webPending.clearPendingLink()).resolves.toBeUndefined();
  });
});
