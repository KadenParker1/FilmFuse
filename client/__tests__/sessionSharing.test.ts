jest.mock('expo-clipboard', () => ({ setStringAsync: jest.fn(() => Promise.resolve(true)) }));

import { Platform, Share } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import * as nativeShare from '../components/sessions/shareSession.native';
import { SESSION_SHARE_MESSAGE } from '../components/sessions/shareSessionTypes';

// jest resolves platform files to .native (ios preset); load the web one explicitly.
const webShare = jest.requireActual<typeof import('../components/sessions/shareSession')>(
  '../components/sessions/shareSession.ts',
);

const URL = 'https://filmfuse.app/sessions/s1';
const setString = Clipboard.setStringAsync as jest.Mock;

describe('shareSession (native)', () => {
  const originalOS = Platform.OS;
  let shareSpy: jest.SpyInstance;
  beforeEach(() => {
    setString.mockClear();
    shareSpy = jest.spyOn(Share, 'share').mockResolvedValue({ action: Share.sharedAction, activityType: undefined });
  });
  afterEach(() => {
    shareSpy.mockRestore();
    Object.defineProperty(Platform, 'OS', { value: originalOS, configurable: true });
  });

  it('iOS passes the link as url beside the message', async () => {
    Object.defineProperty(Platform, 'OS', { value: 'ios', configurable: true });
    await expect(nativeShare.shareSession(URL)).resolves.toBe('shared');
    expect(shareSpy).toHaveBeenCalledWith({ message: SESSION_SHARE_MESSAGE, url: URL }, expect.any(Object));
  });

  it('Android puts the link in the message', async () => {
    Object.defineProperty(Platform, 'OS', { value: 'android', configurable: true });
    await expect(nativeShare.shareSession(URL)).resolves.toBe('shared');
    expect(shareSpy).toHaveBeenCalledWith({ message: `${SESSION_SHARE_MESSAGE} ${URL}` }, expect.any(Object));
  });

  it('reports a dismissed sheet', async () => {
    shareSpy.mockResolvedValue({ action: Share.dismissedAction, activityType: undefined });
    await expect(nativeShare.shareSession(URL)).resolves.toBe('dismissed');
    expect(setString).not.toHaveBeenCalled();
  });
});

describe('shareSession (web)', () => {
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
    await expect(webShare.shareSession(URL)).resolves.toBe('shared');
    expect(share).toHaveBeenCalledWith({ title: 'Pick a movie with us', text: SESSION_SHARE_MESSAGE, url: URL });
    expect(setString).not.toHaveBeenCalled();
  });

  it('treats an AbortError as the user closing the sheet', async () => {
    withShare(jest.fn(() => Promise.reject(Object.assign(new Error('cancel'), { name: 'AbortError' }))));
    await expect(webShare.shareSession(URL)).resolves.toBe('dismissed');
    expect(setString).not.toHaveBeenCalled();
  });

  it('copies the link when Web Share fails otherwise, or is missing', async () => {
    withShare(jest.fn(() => Promise.reject(Object.assign(new Error('nope'), { name: 'NotAllowedError' }))));
    await expect(webShare.shareSession(URL)).resolves.toBe('copied');
    expect(setString).toHaveBeenCalledWith(URL);
    withShare(undefined);
    setString.mockClear();
    await expect(webShare.shareSession(URL)).resolves.toBe('copied');
    expect(setString).toHaveBeenCalledWith(URL);
  });
});
