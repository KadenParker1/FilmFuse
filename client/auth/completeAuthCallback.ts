import { supabase } from '../lib/supabase';

export type CallbackOutcome = { ok: true } | { ok: false; error: string };

/**
 * Finishes an OAuth / email-link redirect. On web supabase-js may already have exchanged the code
 * (detectSessionInUrl), so an existing session wins; otherwise the PKCE code is exchanged here.
 */
export async function completeAuthCallback(params: {
  code?: string;
  error?: string;
}): Promise<CallbackOutcome> {
  if (params.error) return { ok: false, error: params.error };
  try {
    const { data } = await supabase.auth.getSession();
    if (data.session) return { ok: true };
    if (!params.code) return { ok: false, error: 'This sign-in link is missing its code. Try signing in again.' };
    const { error } = await supabase.auth.exchangeCodeForSession(params.code);
    return error ? { ok: false, error: error.message } : { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : 'Sign-in failed. Try again.' };
  }
}
