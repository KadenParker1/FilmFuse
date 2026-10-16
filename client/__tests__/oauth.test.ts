const mockEnv = {
  supabaseUrl: 'https://proj.supabase.co',
  supabaseAnonKey: 'anon',
  apiBaseUrl: 'http://localhost:8080/api/v1',
  isSupabaseConfigured: true,
};
jest.mock('../lib/env', () => ({
  get env() {
    return mockEnv;
  },
}));
jest.mock('../lib/supabase', () => ({
  supabase: {
    auth: {
      signInWithOAuth: jest.fn(),
      exchangeCodeForSession: jest.fn(),
      signInWithIdToken: jest.fn(),
      updateUser: jest.fn(() => Promise.resolve({ error: null })),
    },
  },
}));
jest.mock('expo-linking', () => ({ createURL: jest.fn((path: string) => `filmfuse://${path.replace(/^\//, '')}`) }));
jest.mock('expo-web-browser', () => ({ openAuthSessionAsync: jest.fn() }));
jest.mock('expo-apple-authentication', () => ({
  signInAsync: jest.fn(),
  AppleAuthenticationScope: { FULL_NAME: 0, EMAIL: 1 },
}));

import { Platform } from 'react-native';
import * as AppleAuthentication from 'expo-apple-authentication';
import * as WebBrowser from 'expo-web-browser';
import { authRedirectUrl, isProviderEnabled, parseAuthCallback } from '../auth/authRedirect';
import * as nativeOAuth from '../auth/oauth.native';
import { supabase } from '../lib/supabase';
import { jsonResponse } from '../test-utils/render';

// jest resolves '../auth/oauth' to the .native file (ios preset); load the web one explicitly.
const webOAuth = jest.requireActual<typeof import('../auth/oauth')>('../auth/oauth.ts');
const auth = supabase.auth as unknown as Record<string, jest.Mock>;
const openAuthSession = WebBrowser.openAuthSessionAsync as jest.Mock;
const appleSignIn = AppleAuthentication.signInAsync as jest.Mock;
const fetchMock = jest.fn();

const settings = (external: Record<string, boolean>) => jsonResponse(200, { external });

beforeEach(() => {
  jest.clearAllMocks();
  mockEnv.isSupabaseConfigured = true;
  global.fetch = fetchMock as unknown as typeof fetch;
  fetchMock.mockResolvedValue(settings({ google: true, apple: true, email: true }));
  auth.signInWithOAuth!.mockResolvedValue({ data: { url: 'https://proj.supabase.co/auth/v1/authorize?provider=google' }, error: null });
  auth.exchangeCodeForSession!.mockResolvedValue({ data: {}, error: null });
  auth.signInWithIdToken!.mockResolvedValue({ data: {}, error: null });
});

describe('authRedirect', () => {
  it('builds the callback URL', () => {
    expect(authRedirectUrl()).toBe('filmfuse://auth/callback');
  });

  it.each([
    ['filmfuse://auth/callback?code=abc123', { code: 'abc123', error: undefined }],
    ['https://app.example/auth/callback?code=x%2By&state=1', { code: 'x+y', error: undefined }],
    ['filmfuse://auth/callback#error=access_denied&error_description=User+cancelled', { code: undefined, error: 'User cancelled' }],
    ['filmfuse://auth/callback?error=server_error', { code: undefined, error: 'server_error' }],
    ['filmfuse://auth/callback', { code: undefined, error: undefined }],
  ])('parses %p', (url, expected) => {
    expect(parseAuthCallback(url)).toEqual(expected);
  });

  it('reads provider status from Supabase settings with the anon key', async () => {
    fetchMock.mockResolvedValue(settings({ google: false, apple: true }));
    await expect(isProviderEnabled('google')).resolves.toBe(false);
    await expect(isProviderEnabled('apple')).resolves.toBe(true);
    expect(fetchMock).toHaveBeenCalledWith('https://proj.supabase.co/auth/v1/settings', { headers: { apikey: 'anon' } });
  });

  it('treats an unreachable settings endpoint as enabled, and no config as disabled', async () => {
    fetchMock.mockRejectedValue(new TypeError('offline'));
    await expect(isProviderEnabled('google')).resolves.toBe(true);
    mockEnv.isSupabaseConfigured = false;
    await expect(isProviderEnabled('google')).resolves.toBe(false);
  });
});

describe('native signInWithProvider', () => {
  it('Google: opens an auth session and exchanges the returned PKCE code', async () => {
    openAuthSession.mockResolvedValue({ type: 'success', url: 'filmfuse://auth/callback?code=the-code' });
    await expect(nativeOAuth.signInWithProvider('google')).resolves.toEqual({ ok: true });
    expect(auth.signInWithOAuth).toHaveBeenCalledWith({
      provider: 'google',
      options: { redirectTo: 'filmfuse://auth/callback', skipBrowserRedirect: true },
    });
    expect(openAuthSession).toHaveBeenCalledWith(
      'https://proj.supabase.co/auth/v1/authorize?provider=google',
      'filmfuse://auth/callback',
    );
    expect(auth.exchangeCodeForSession).toHaveBeenCalledWith('the-code');
  });

  it('Google: a dismissed browser is a silent cancel', async () => {
    openAuthSession.mockResolvedValue({ type: 'dismiss' });
    await expect(nativeOAuth.signInWithProvider('google')).resolves.toEqual({ ok: false, cancelled: true });
    expect(auth.exchangeCodeForSession).not.toHaveBeenCalled();
  });

  it('Google: surfaces a provider error from the callback', async () => {
    openAuthSession.mockResolvedValue({
      type: 'success',
      url: 'filmfuse://auth/callback?error=server_error&error_description=Unable+to+exchange+external+code',
    });
    await expect(nativeOAuth.signInWithProvider('google')).resolves.toEqual({
      ok: false,
      error: 'Unable to exchange external code',
    });
  });

  it('Google: surfaces a failed code exchange', async () => {
    openAuthSession.mockResolvedValue({ type: 'success', url: 'filmfuse://auth/callback?code=c' });
    auth.exchangeCodeForSession!.mockResolvedValue({ data: {}, error: { message: 'invalid flow state' } });
    await expect(nativeOAuth.signInWithProvider('google')).resolves.toEqual({ ok: false, error: 'invalid flow state' });
  });

  it('reports a provider that is not enabled without opening a browser', async () => {
    fetchMock.mockResolvedValue(settings({ google: false }));
    const result = await nativeOAuth.signInWithProvider('google');
    expect(result).toEqual({ ok: false, error: expect.stringContaining("Google sign-in isn't set up") });
    expect(openAuthSession).not.toHaveBeenCalled();
  });

  it('Apple on iOS: uses the native sheet and signs in with the identity token', async () => {
    expect(Platform.OS).toBe('ios');
    expect(nativeOAuth.showAppleButton).toBe(true);
    appleSignIn.mockResolvedValue({ identityToken: 'id-token', fullName: { givenName: 'Ada', familyName: 'Lovelace' } });
    await expect(nativeOAuth.signInWithProvider('apple')).resolves.toEqual({ ok: true });
    expect(appleSignIn).toHaveBeenCalledWith({ requestedScopes: [0, 1] });
    expect(auth.signInWithIdToken).toHaveBeenCalledWith({ provider: 'apple', token: 'id-token' });
    expect(auth.updateUser).toHaveBeenCalledWith({ data: { full_name: 'Ada Lovelace' } });
    expect(openAuthSession).not.toHaveBeenCalled();
  });

  it('Apple on iOS: cancelling is silent', async () => {
    appleSignIn.mockRejectedValue(Object.assign(new Error('canceled'), { code: 'ERR_REQUEST_CANCELED' }));
    await expect(nativeOAuth.signInWithProvider('apple')).resolves.toEqual({ ok: false, cancelled: true });
  });

  it('Apple on iOS: Supabase errors are reported', async () => {
    appleSignIn.mockResolvedValue({ identityToken: 'id-token', fullName: null });
    auth.signInWithIdToken!.mockResolvedValue({ data: {}, error: { message: 'Provider apple is not enabled' } });
    await expect(nativeOAuth.signInWithProvider('apple')).resolves.toEqual({
      ok: false,
      error: 'Provider apple is not enabled',
    });
  });
});

describe('web signInWithProvider', () => {
  it('redirects the page via Supabase with the callback URL', async () => {
    expect(webOAuth.showAppleButton).toBe(true);
    await expect(webOAuth.signInWithProvider('apple')).resolves.toEqual({ ok: true });
    expect(auth.signInWithOAuth).toHaveBeenCalledWith({
      provider: 'apple',
      options: { redirectTo: 'filmfuse://auth/callback' },
    });
  });

  it('reports errors and unconfigured providers inline', async () => {
    auth.signInWithOAuth!.mockResolvedValue({ data: {}, error: { message: 'bad' } });
    await expect(webOAuth.signInWithProvider('google')).resolves.toEqual({ ok: false, error: 'bad' });
    fetchMock.mockResolvedValue(settings({ google: false }));
    await expect(webOAuth.signInWithProvider('google')).resolves.toMatchObject({ ok: false });
  });
});
