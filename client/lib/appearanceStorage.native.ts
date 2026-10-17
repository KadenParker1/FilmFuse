/** Native: the Appearance setting lives in SecureStore (per device). Unknown values read as null. */
import * as SecureStore from 'expo-secure-store';
import { APPEARANCE_KEY, isAppearancePreference, type AppearancePreference } from './appearancePreference';

export async function getAppearance(): Promise<AppearancePreference | null> {
  try {
    const value = await SecureStore.getItemAsync(APPEARANCE_KEY);
    return isAppearancePreference(value) ? value : null;
  } catch {
    return null;
  }
}

export async function setAppearance(pref: AppearancePreference): Promise<void> {
  try {
    await SecureStore.setItemAsync(APPEARANCE_KEY, pref);
  } catch {
    // the choice still applies until the app restarts
  }
}
