import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import type { MoodDTO } from '../types/api';

export const fetchMoods = () => apiClient.get<MoodDTO[]>('/moods');

/** The fixed mood ("vibe") dimensions. They only change with a release, so they're cached for the session. */
export function useMoods() {
  return useQuery({ queryKey: queryKeys.moods, queryFn: fetchMoods, staleTime: Infinity });
}
