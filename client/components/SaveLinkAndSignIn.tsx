import { useEffect, useRef } from 'react';
import { useRouter } from 'expo-router';
import { setPendingLink } from '../lib/pendingLink';
import { FullScreenLoader } from './FullScreenLoader';

/**
 * Signed-out landing for a deep link that needs an account (invite, profile): remembers `path`
 * for after sign-in (see components/social/PendingLinkRedeemer), then goes to sign in.
 */
export function SaveLinkAndSignIn({ path, label }: { path: string; label: string }) {
  const router = useRouter();
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void setPendingLink(path)
      .catch(() => {
        // Storage unavailable: the link is lost, but signing in still works.
      })
      .finally(() => router.replace('/sign-in'));
  }, [path, router]);

  return <FullScreenLoader label={label} />;
}
