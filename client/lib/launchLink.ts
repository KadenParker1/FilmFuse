/**
 * Web: the path the page was loaded at, read once when the bundle starts — before the sign-in guard
 * redirects a signed-out visitor away from it. Guarded like authStorage because the static web
 * export evaluates modules in Node.
 */
import { pathFromUrl } from './pendingLinkPath';

function readLocation(): string | null {
  try {
    return typeof window !== 'undefined' && window.location ? pathFromUrl(window.location.href) : null;
  } catch {
    return null;
  }
}

let launchPath: string | null = readLocation();

/** The launch path, once; later calls get null (so a later sign-out doesn't reuse it). */
export async function takeLaunchPath(): Promise<string | null> {
  const path = launchPath;
  launchPath = null;
  return path;
}

/** Web pages don't receive links while open (each link is a page load). */
export function subscribeToLinks(_onPath: (path: string) => void): () => void {
  return () => {};
}

/** Tests only: pretend the page was loaded at `url`. */
export function setLaunchUrlForTests(url: string | null): void {
  launchPath = pathFromUrl(url);
}
