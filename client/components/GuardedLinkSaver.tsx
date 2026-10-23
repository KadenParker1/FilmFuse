import { useEffect, useRef } from 'react';
import { subscribeToLinks, takeLaunchPath } from '../lib/launchLink';
import { setPendingLink } from '../lib/pendingLink';
import { isGuardedLinkPath } from '../lib/pendingLinkPath';

const save = (path: string | null) => {
  if (!isGuardedLinkPath(path)) return;
  void setPendingLink(path).catch(() => {
    // Storage unavailable: the link is lost, but signing in still works.
  });
};

/**
 * Session and group screens sit behind the sign-in guard, which sends a signed-out visitor to sign
 * in before the screen could remember where they were going. This remembers such a link (the one
 * the app or page was opened with, and on native any that arrive while signed out) so
 * PendingLinkRedeemer opens it after sign-in and onboarding. Only `/sessions/<uuid>` and
 * `/groups/<uuid>` are kept; the unguarded link screens (invite, profile, rating) save themselves.
 * Mounted by the root navigator once auth has loaded.
 */
export function GuardedLinkSaver({ signedIn }: { signedIn: boolean }) {
  // Only the state at launch matters for the launch link.
  const signedInAtLaunch = useRef(signedIn);
  // The launch link is read once whatever the state, so a later sign-out never reuses it.
  useEffect(() => {
    void takeLaunchPath()
      .then((path) => {
        if (!signedInAtLaunch.current) save(path);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (signedIn) return;
    return subscribeToLinks(save);
  }, [signedIn]);

  return null;
}
