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
