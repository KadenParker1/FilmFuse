import { useEffect, useRef } from 'react';
import { useRootNavigationState, useRouter, type Href } from 'expo-router';
import { clearPendingLink, getPendingLink } from '../../lib/pendingLink';

/**
 * Picks up a link opened while signed out — an invite (saved by app/invite/[token]) or a profile
 * (saved by app/user/[id], e.g. from an email). Mounted inside the (tabs) layout, so it runs once
 * the user is signed in and onboarded: it clears the saved link first — so it's used exactly once —
 * then opens it; the invite route redeems the token itself.
 */
export function PendingLinkRedeemer() {
  const router = useRouter();
  const navReady = !!useRootNavigationState()?.key;
  const checked = useRef(false);

  useEffect(() => {
    if (!navReady || checked.current) return;
    checked.current = true;
    void (async () => {
      const path = await getPendingLink().catch(() => null);
      if (!path) return;
      await clearPendingLink().catch(() => {});
      // Only the allow-listed shapes (/invite/…, /user/…, /ratings/…, /sessions/<uuid>, /groups/<uuid>)
      // are ever stored or read back. Mounted inside ProfileGate, so this waits for onboarding too.
      router.push(path as Href);
    })();
  }, [navReady, router]);

  return null;
}
