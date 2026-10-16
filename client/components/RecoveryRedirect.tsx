import { useEffect } from 'react';
import { usePathname, useRootNavigationState, useRouter } from 'expo-router';
import { RESET_PASSWORD_ROUTE } from '../auth/authRedirect';
import { useAuth } from '../auth/useAuth';

/**
 * Supabase emits PASSWORD_RECOVERY when a reset link signs the user in (native code exchange, or
 * supabase-js picking the code up from the URL on web). Wherever they landed, send them to set a
 * new password. The callback route also routes there itself via `type=recovery`.
 */
export function RecoveryRedirect() {
  const { passwordRecovery } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const navReady = !!useRootNavigationState()?.key;

  useEffect(() => {
    if (navReady && passwordRecovery && pathname !== RESET_PASSWORD_ROUTE) router.replace(RESET_PASSWORD_ROUTE);
  }, [navReady, passwordRecovery, pathname, router]);

  return null;
}
