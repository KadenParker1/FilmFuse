import { createClient } from '@supabase/supabase-js';
import { authStorage, detectSessionInUrl } from './authStorage';
import { env } from './env';

/**
 * The one Supabase client. Only used for auth (and later Realtime) — all app data goes through
 * core-api via apiClient.
 *
 * When the env vars are missing we still create a client (pointed at the local Supabase CLI default)
 * so the app and the static web export load; the sign-in screen surfaces `env.isSupabaseConfigured`.
 */
export const supabase = createClient(
  env.supabaseUrl || 'http://127.0.0.1:54321',
  env.supabaseAnonKey || 'missing-anon-key',
  {
    auth: {
      storage: authStorage,
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl,
      // PKCE: OAuth redirects come back with ?code=…, exchanged in app/auth/callback.tsx (and by
      // supabase-js itself on web via detectSessionInUrl).
      flowType: 'pkce',
    },
  },
);
