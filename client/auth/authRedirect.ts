import * as Linking from 'expo-linking';
import { env } from '../lib/env';

export type OAuthProvider = 'google' | 'apple';

export const PROVIDER_NAMES: Record<OAuthProvider, string> = { google: 'Google', apple: 'Apple' };

/**
 * Where Supabase sends the browser back after OAuth, email confirmation or password reset:
 * `filmfuse://auth/callback` in a native build, `<origin>/auth/callback` on web. Must be listed in
 * Supabase Auth → URL Configuration → Redirect URLs.
 */
export function authRedirectUrl(): string {
  return Linking.createURL('/auth/callback');
}

/** Password-reset emails come back marked, so the callback can route to the reset screen. */
export function recoveryRedirectUrl(): string {
  return Linking.createURL('/auth/callback', { queryParams: { type: 'recovery' } });
}

export const RESET_PASSWORD_ROUTE = '/auth/reset-password';

export type CallbackParams = { code?: string; error?: string };

/** Reads `code` / `error_description` from a callback URL's query string or fragment. */
export function parseAuthCallback(url: string): CallbackParams {
  const params: Record<string, string> = {};
  const parts = [url.split('?')[1]?.split('#')[0], url.split('#')[1]];
  for (const part of parts) {
    if (!part) continue;
    for (const pair of part.split('&')) {
      const [k, v = ''] = pair.split('=');
      if (k) params[decodeURIComponent(k)] = decodeURIComponent(v.replace(/\+/g, ' '));
    }
  }
  const error = params.error_description || params.error;
  return { code: params.code || undefined, error: error || undefined };
}

/** A sign-in attempt's result. `cancelled` = the user backed out; show nothing. */
export type OAuthResult = { ok: true } | { ok: false; cancelled?: boolean; error?: string };

/**
 * Asks Supabase whether a provider is switched on, so "not configured" is shown inline instead of
 * dumping the user on a raw JSON error page. Unknown (network trouble) counts as enabled.
 */
export async function isProviderEnabled(provider: OAuthProvider): Promise<boolean> {
  if (!env.isSupabaseConfigured) return false;
  try {
    const res = await fetch(`${env.supabaseUrl.replace(/\/+$/, '')}/auth/v1/settings`, {
      headers: { apikey: env.supabaseAnonKey },
    });
    if (!res.ok) return true;
    const body = (await res.json()) as { external?: Record<string, boolean> };
    return body.external?.[provider] !== false;
  } catch {
    return true;
  }
}

export const notConfiguredMessage = (provider: OAuthProvider) =>
  `${PROVIDER_NAMES[provider]} sign-in isn't set up for this FilmFuse server yet. Use email for now.`;
