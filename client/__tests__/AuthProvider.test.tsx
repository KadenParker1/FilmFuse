type Listener = (event: string, session: unknown) => void;
let mockListener: Listener = () => {};
jest.mock('../lib/supabase', () => ({
  supabase: {
    auth: {
      getSession: jest.fn(() => Promise.resolve({ data: { session: null } })),
      onAuthStateChange: jest.fn((cb: Listener) => {
        mockListener = cb;
        return { data: { subscription: { unsubscribe: jest.fn() } } };
      }),
      resetPasswordForEmail: jest.fn(() => Promise.resolve({ data: {}, error: null })),
      updateUser: jest.fn(() => Promise.resolve({ data: {}, error: null })),
      signOut: jest.fn(() => Promise.resolve({ error: null })),
    },
  },
}));
jest.mock('expo-linking', () => ({
  createURL: (path: string, opts?: { queryParams?: Record<string, string> }) =>
    `filmfuse://${path.replace(/^\//, '')}${opts?.queryParams ? `?${new URLSearchParams(opts.queryParams)}` : ''}`,
}));

import { act, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { AuthProvider } from '../auth/AuthProvider';
import { useAuth } from '../auth/useAuth';
import { supabase } from '../lib/supabase';
import { createTestQueryClient } from '../test-utils/render';
import { renderHook } from '@testing-library/react-native';

const client = createTestQueryClient();
const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={client}>
    <AuthProvider>{children}</AuthProvider>
  </QueryClientProvider>
);

describe('AuthProvider password recovery', () => {
  it('flags PASSWORD_RECOVERY until cleared, and clears on sign-out', async () => {
    const { result } = await renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.passwordRecovery).toBe(false);

    await act(async () => mockListener('PASSWORD_RECOVERY', { access_token: 't' }));
    expect(result.current.passwordRecovery).toBe(true);
    expect(result.current.session).toEqual({ access_token: 't' });

    await act(async () => result.current.clearPasswordRecovery());
    expect(result.current.passwordRecovery).toBe(false);

    await act(async () => mockListener('PASSWORD_RECOVERY', { access_token: 't' }));
    await act(async () => mockListener('SIGNED_OUT', null));
    expect(result.current.passwordRecovery).toBe(false);
  });

  it('sends reset emails back marked as recovery, and updates the password', async () => {
    const { result } = await renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      await result.current.resetPassword('ada@example.com');
      await result.current.updatePassword('brand-new-pw');
    });
    expect(supabase.auth.resetPasswordForEmail).toHaveBeenCalledWith('ada@example.com', {
      redirectTo: 'filmfuse://auth/callback?type=recovery',
    });
    expect(supabase.auth.updateUser).toHaveBeenCalledWith({ password: 'brand-new-pw' });
  });
});

describe('AuthProvider cold load (bug 5: a deep link bounced to "/")', () => {
  afterEach(() => {
    (supabase.auth.getSession as jest.Mock).mockImplementation(() => Promise.resolve({ data: { session: null } }));
    jest.useRealTimers();
  });

  it('stays loading when the session read fails, until INITIAL_SESSION brings the real session', async () => {
    (supabase.auth.getSession as jest.Mock).mockImplementation(() => Promise.reject(new Error('lock timed out')));
    const { result } = await renderHook(() => useAuth(), { wrapper });
    await act(async () => {});
    // A guessed "signed out" here would send /movie/12 to sign-in and then to "/".
    expect(result.current.loading).toBe(true);
    expect(result.current.session).toBeNull();

    await act(async () => mockListener('INITIAL_SESSION', { access_token: 'real' }));
    expect(result.current.loading).toBe(false);
    expect(result.current.session).toEqual({ access_token: 'real' });
  });

  it('falls back to signed out if INITIAL_SESSION never comes after a failed read', async () => {
    jest.useFakeTimers();
    (supabase.auth.getSession as jest.Mock).mockImplementation(() => Promise.reject(new Error('broken')));
    const { result } = await renderHook(() => useAuth(), { wrapper });
    await act(async () => {});
    expect(result.current.loading).toBe(true);
    await act(async () => jest.advanceTimersByTime(5_000));
    expect(result.current.loading).toBe(false);
    expect(result.current.session).toBeNull();
  });

  it('ends loading with the stored session on a successful read', async () => {
    (supabase.auth.getSession as jest.Mock).mockImplementation(() =>
      Promise.resolve({ data: { session: { access_token: 'stored' } } }),
    );
    const { result } = await renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.session).toEqual({ access_token: 'stored' });
  });
});
