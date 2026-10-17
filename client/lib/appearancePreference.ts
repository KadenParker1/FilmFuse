/** Appearance setting: Dark (the default, whatever the system says), Light, or follow the System. */
export type AppearancePreference = 'dark' | 'light' | 'system';

export const APPEARANCE_KEY = 'filmfuse.appearance';
export const DEFAULT_APPEARANCE: AppearancePreference = 'dark';

export function isAppearancePreference(value: unknown): value is AppearancePreference {
  return value === 'dark' || value === 'light' || value === 'system';
}

/** The colour scheme a preference resolves to, given the system scheme (null when unknown). */
export function resolveScheme(pref: AppearancePreference, system: string | null | undefined): 'dark' | 'light' {
  if (pref === 'system') return system === 'light' ? 'light' : 'dark';
  return pref;
}
