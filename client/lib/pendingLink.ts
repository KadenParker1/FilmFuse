/**
 * Web: a link opened while signed out (an invite, or a profile from an email) is remembered in
 * localStorage until sign-in, when the tabs layout opens it (components/social/PendingLinkRedeemer).
 * Guarded like authStorage because the static web export evaluates modules in Node.
 */
import { isPendingLinkPath } from './pendingLinkPath';

export const PENDING_LINK_KEY = 'filmfuse.pendingLink';

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' && window.localStorage ? window.localStorage : null;
  } catch {
    return null;
  }
}

/** The saved in-app path, or null (also for anything that isn't an invite / profile path). */
export async function getPendingLink(): Promise<string | null> {
  const path = storage()?.getItem(PENDING_LINK_KEY) ?? null;
  return isPendingLinkPath(path) ? path : null;
}

export async function setPendingLink(path: string): Promise<void> {
  if (isPendingLinkPath(path)) storage()?.setItem(PENDING_LINK_KEY, path);
}

export async function clearPendingLink(): Promise<void> {
  storage()?.removeItem(PENDING_LINK_KEY);
}
