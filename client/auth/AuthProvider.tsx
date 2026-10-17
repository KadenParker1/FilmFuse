import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { Session, User } from '@supabase/supabase-js';
import { useQueryClient } from '@tanstack/react-query';
import { supabase } from '../lib/supabase';
import { useAutoRefreshWhileActive } from './useAutoRefreshWhileActive';
import { authRedirectUrl, recoveryRedirectUrl } from './authRedirect';

export interface AuthContextValue {
  session: Session | null;
  user: User | null;
  /** True until the stored session has been read; render nothing route-dependent before that. */
  loading: boolean;
  signInWithPassword: (email: string, password: string) => ReturnType<typeof supabase.auth.signInWithPassword>;
  signUp: (email: string, password: string) => ReturnType<typeof supabase.auth.signUp>;
  /** Emails a reset link that lands on /auth/callback. */
  resetPassword: (email: string) => ReturnType<typeof supabase.auth.resetPasswordForEmail>;
  signOut: () => Promise<void>;
  /** Sets a new password for the signed-in (e.g. recovering) user. */
  updatePassword: (password: string) => ReturnType<typeof supabase.auth.updateUser>;
  /** True after a PASSWORD_RECOVERY event until the new password is set (or the user leaves). */
  passwordRecovery: boolean;
  clearPasswordRecovery: () => void;
}

/** After a failed session read, how long to wait for INITIAL_SESSION before assuming signed out. */
export const SESSION_READ_FALLBACK_MS = 5_000;

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [passwordRecovery, setPasswordRecovery] = useState(false);

  useEffect(() => {
    let mounted = true;
    // Route guards must not run on a guessed "signed out": if getSession() fails (e.g. the web
    // storage lock timed out on a cold load), a null session would bounce a deep link such as
    // /movie/12 to sign-in and then, once the INITIAL_SESSION event delivers the real session, on
    // to "/". So only a successful read - or supabase's INITIAL_SESSION event - ends loading; a
    // failed read waits for that event, with a timeout so a broken store can't hang the app.
    let fallback: ReturnType<typeof setTimeout> | undefined;
    const settle = (next: Session | null) => {
      if (!mounted) return;
      if (fallback) clearTimeout(fallback);
      setSession(next);
      setLoading(false);
    };
    supabase.auth
      .getSession()
      .then(({ data }) => settle(data.session))
      .catch(() => {
        fallback = setTimeout(() => settle(null), SESSION_READ_FALLBACK_MS);
      });

    const { data } = supabase.auth.onAuthStateChange((event, next) => {
      if (event === 'INITIAL_SESSION') {
        settle(next);
        return;
      }
      setSession(next);
      if (event === 'PASSWORD_RECOVERY') setPasswordRecovery(true);
      // Another user's cached data must never leak into the next session.
      if (event === 'SIGNED_OUT') {
        queryClient.clear();
        setPasswordRecovery(false);
      }
    });
    return () => {
      mounted = false;
      if (fallback) clearTimeout(fallback);
      data.subscription.unsubscribe();
    };
  }, [queryClient]);

  useAutoRefreshWhileActive();

  const clearPasswordRecovery = useCallback(() => setPasswordRecovery(false), []);

  const signOut = useCallback(async () => {
    await supabase.auth.signOut();
    queryClient.clear();
  }, [queryClient]);

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      user: session?.user ?? null,
      loading,
      signInWithPassword: (email, password) => supabase.auth.signInWithPassword({ email, password }),
      signUp: (email, password) =>
        supabase.auth.signUp({ email, password, options: { emailRedirectTo: authRedirectUrl() } }),
      resetPassword: (email) => supabase.auth.resetPasswordForEmail(email, { redirectTo: recoveryRedirectUrl() }),
      updatePassword: (password) => supabase.auth.updateUser({ password }),
      passwordRecovery,
      clearPasswordRecovery,
      signOut,
    }),
    [session, loading, signOut, passwordRecovery, clearPasswordRecovery],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
