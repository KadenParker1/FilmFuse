import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import type { ComfortSettingsDTO } from '../types/api';

export const COMFORT_PATH = '/me/settings/comfort';

export const fetchComfortSettings = () => apiClient.get<ComfortSettingsDTO>(COMFORT_PATH);

/** The caller's content comfort settings (defaults: no limit, no dimension limits, unrated off). */
export function useComfortSettings() {
  return useQuery({ queryKey: queryKeys.comfortSettings, queryFn: fetchComfortSettings });
}

/**
 * Replaces the comfort settings. Every group's effective filter may change with them, so cached
 * group details are refetched.
 */
export function useUpdateComfortSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ComfortSettingsDTO) => apiClient.put<ComfortSettingsDTO>(COMFORT_PATH, body),
    onSuccess: (settings) => {
      qc.setQueryData(queryKeys.comfortSettings, settings);
      void qc.invalidateQueries({ queryKey: queryKeys.groups });
    },
  });
}
