/**
 * Native: the link the app was cold-started with (read once), and links that arrive while it is
 * running. Read here because the sign-in guard redirects a signed-out user before the target screen
 * could save the link itself.
 */
import * as Linking from 'expo-linking';
import { pathFromUrl } from './pendingLinkPath';

let taken = false;

/** The launch path, once; later calls get null (so a later sign-out doesn't reuse it). */
export async function takeLaunchPath(): Promise<string | null> {
  if (taken) return null;
  taken = true;
  return pathFromUrl(await Linking.getInitialURL());
}

/** Calls `onPath` for every link opened while the app runs; returns the unsubscribe. */
export function subscribeToLinks(onPath: (path: string) => void): () => void {
  const sub = Linking.addEventListener('url', ({ url }) => {
    const path = pathFromUrl(url);
    if (path) onPath(path);
  });
  return () => sub.remove();
}

/** Tests only: allow takeLaunchPath() again. */
export function setLaunchUrlForTests(_url?: string | null): void {
  taken = false;
}
