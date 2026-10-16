/**
 * Native OAuth. Google (and Apple off iOS) open Supabase's authorize URL in an auth session
 * (ASWebAuthenticationSession / Custom Tabs) and exchange the returned PKCE code. Apple on iOS uses
 * the native sheet and hands its identity token to Supabase.
 */
import { Platform } from 'react-native';
import * as AppleAuthentication from 'expo-apple-authentication';
import * as WebBrowser from 'expo-web-browser';
import { supabase } from '../lib/supabase';
import {
  authRedirectUrl,
  isProviderEnabled,
  notConfiguredMessage,
  parseAuthCallback,
  type OAuthProvider,
  type OAuthResult,
} from './authRedirect';

/** Apple's button shows on iOS and web only (Apple requires it there because Google is offered). */
export const showAppleButton = Platform.OS === 'ios';

const errorText = (e: unknown) => (e instanceof Error && e.message ? e.message : 'Sign-in failed. Try again.');

async function browserOAuth(provider: OAuthProvider): Promise<OAuthResult> {
  const redirectTo = authRedirectUrl();
  const { data, error } = await supabase.auth.signInWithOAuth({
    provider,
    options: { redirectTo, skipBrowserRedirect: true },
  });
  if (error) return { ok: false, error: error.message };
  if (!data.url) return { ok: false, error: 'Sign-in failed. Try again.' };

  const result = await WebBrowser.openAuthSessionAsync(data.url, redirectTo);
  if (result.type !== 'success') return { ok: false, cancelled: true };

  const { code, error: callbackError } = parseAuthCallback(result.url);
  if (callbackError) return { ok: false, error: callbackError };
  if (!code) return { ok: false, error: 'Sign-in failed: no authorization code came back.' };
  const { error: exchangeError } = await supabase.auth.exchangeCodeForSession(code);
  return exchangeError ? { ok: false, error: exchangeError.message } : { ok: true };
}

async function nativeApple(): Promise<OAuthResult> {
  try {
    const credential = await AppleAuthentication.signInAsync({
      requestedScopes: [
        AppleAuthentication.AppleAuthenticationScope.FULL_NAME,
        AppleAuthentication.AppleAuthenticationScope.EMAIL,
      ],
    });
    if (!credential.identityToken) return { ok: false, error: 'Apple did not return an identity token.' };
    const { error } = await supabase.auth.signInWithIdToken({ provider: 'apple', token: credential.identityToken });
    if (error) return { ok: false, error: error.message };
    // Apple only shares the name on the very first sign-in; keep it (best-effort).
    const fullName = [credential.fullName?.givenName, credential.fullName?.familyName].filter(Boolean).join(' ');
    if (fullName) await supabase.auth.updateUser({ data: { full_name: fullName } }).catch(() => {});
    return { ok: true };
  } catch (e) {
    if ((e as { code?: string }).code === 'ERR_REQUEST_CANCELED') return { ok: false, cancelled: true };
    return { ok: false, error: errorText(e) };
  }
}

export async function signInWithProvider(provider: OAuthProvider): Promise<OAuthResult> {
  try {
    if (!(await isProviderEnabled(provider))) return { ok: false, error: notConfiguredMessage(provider) };
    if (provider === 'apple' && Platform.OS === 'ios') return await nativeApple();
    return await browserOAuth(provider);
  } catch (e) {
    return { ok: false, error: errorText(e) };
  }
}
