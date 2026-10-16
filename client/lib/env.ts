/**
 * Build-time configuration. Expo inlines `process.env.EXPO_PUBLIC_*` into the bundle, so these must
 * be read with the full literal name (no dynamic lookup).
 */
const supabaseUrl = process.env.EXPO_PUBLIC_SUPABASE_URL ?? '';
const supabaseAnonKey = process.env.EXPO_PUBLIC_SUPABASE_ANON_KEY ?? '';

export const env = {
  supabaseUrl,
  supabaseAnonKey,
  apiBaseUrl: (process.env.EXPO_PUBLIC_API_BASE_URL || 'http://localhost:8080/api/v1').replace(/\/+$/, ''),
  /** False until .env.local is filled in; the sign-in screen says so instead of crashing. */
  isSupabaseConfigured: supabaseUrl.length > 0 && supabaseAnonKey.length > 0,
} as const;
