/**
 * Web: the Appearance setting lives in localStorage (per browser). Guarded like pendingLink because
 * the static web export evaluates modules in Node. Unknown or missing values read as null.
 */
import { APPEARANCE_KEY, isAppearancePreference, type AppearancePreference } from './appearancePreference';

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' && window.localStorage ? window.localStorage : null;
  } catch {
    return null;
  }
}

export async function getAppearance(): Promise<AppearancePreference | null> {
  try {
    const value = storage()?.getItem(APPEARANCE_KEY) ?? null;
    return isAppearancePreference(value) ? value : null;
  } catch {
    return null;
  }
}

export async function setAppearance(pref: AppearancePreference): Promise<void> {
  try {
    storage()?.setItem(APPEARANCE_KEY, pref);
  } catch {
    // private mode / blocked storage: the choice still applies for this visit
  }
}
