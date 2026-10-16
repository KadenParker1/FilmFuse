/**
 * Web OAuth: a plain full-page redirect to Supabase → provider → back to /auth/callback, where the
 * PKCE code is exchanged (app/auth/callback.tsx).
 */
import { supabase } from '../lib/supabase';
import { authRedirectUrl, isProviderEnabled, notConfiguredMessage, type OAuthProvider, type OAuthResult } from './authRedirect';

/** Apple's button shows on iOS and web. */
export const showAppleButton = true;

export async function signInWithProvider(provider: OAuthProvider): Promise<OAuthResult> {
  if (!(await isProviderEnabled(provider))) return { ok: false, error: notConfiguredMessage(provider) };
  const { error } = await supabase.auth.signInWithOAuth({ provider, options: { redirectTo: authRedirectUrl() } });
  if (error) return { ok: false, error: error.message };
  // The page is navigating away; nothing more to do.
  return { ok: true };
}
