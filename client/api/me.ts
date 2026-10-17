import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { supabase } from '../lib/supabase';
import type { ProfileResponseDTO, UpdateProfileRequestDTO } from '../types/api';

export const fetchMe = () => apiClient.get<ProfileResponseDTO>('/me');

/** The signed-in user's profile. The first call provisions it server-side. */
export function useMe(options: { enabled?: boolean } = {}) {
  return useQuery({ queryKey: queryKeys.me, queryFn: fetchMe, enabled: options.enabled ?? true });
}

export function useUpdateMe() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateProfileRequestDTO) => apiClient.patch<ProfileResponseDTO>('/me', body),
    onSuccess: (profile) => qc.setQueryData(queryKeys.me, profile),
  });
}

/**
 * Marks onboarding complete. core-api requires a username, ≥5 genre prefs (≥3 scored 8+) and ≥3
 * favourites, and starts the first taste-vector build. Caching the returned profile is what lets
 * ProfileGate through to the tabs.
 */
export function useCompleteOnboarding() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiClient.post<ProfileResponseDTO>('/me/onboarding/complete'),
    onSuccess: (profile) => {
      qc.setQueryData(queryKeys.me, profile);
      void qc.invalidateQueries({ queryKey: queryKeys.onboarding });
      void qc.invalidateQueries({ queryKey: queryKeys.taste });
      void qc.invalidateQueries({ queryKey: queryKeys.recommendations });
    },
  });
}

/**
 * Permanently deletes the account (profile, photo and Supabase Auth user), then signs out locally
 * and drops every cached query so nothing of the old account lingers.
 */
export function useDeleteAccount() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiClient.del<void>('/me'),
    onSuccess: async () => {
      // The auth user is already gone server-side; a failing remote sign-out still clears locally.
      await supabase.auth.signOut({ scope: 'local' }).catch(() => {});
      qc.clear();
    },
  });
}
