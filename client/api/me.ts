import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
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
