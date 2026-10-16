/**
 * Native: a link opened while signed out (an invite, or a profile from an email) is remembered in
 * SecureStore until sign-in, when the tabs layout opens it (components/social/PendingLinkRedeemer).
 */
import * as SecureStore from 'expo-secure-store';
import { isPendingLinkPath } from './pendingLinkPath';

/** SecureStore keys allow only [A-Za-z0-9._-]. */
export const PENDING_LINK_KEY = 'filmfuse.pendingLink';

/** The saved in-app path, or null (also for anything that isn't an invite / profile path). */
export async function getPendingLink(): Promise<string | null> {
  const path = await SecureStore.getItemAsync(PENDING_LINK_KEY);
  return isPendingLinkPath(path) ? path : null;
}

export async function setPendingLink(path: string): Promise<void> {
  if (isPendingLinkPath(path)) await SecureStore.setItemAsync(PENDING_LINK_KEY, path);
}

export function clearPendingLink(): Promise<void> {
  return SecureStore.deleteItemAsync(PENDING_LINK_KEY);
}
