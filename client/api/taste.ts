import { useQuery, type QueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type { TasteHistoryEntryDTO, TasteProfileDTO } from '../types/api';

/** How often the taste profile is re-read while a rebuild is pending. */
export const TASTE_POLL_MS = 3_000;

export const fetchTasteProfile = () => apiClient.get<TasteProfileDTO>('/me/taste-profile');

export const fetchTasteHistory = (limit?: number | null) =>
  apiClient.get<TasteHistoryEntryDTO[]>(withQuery('/me/taste/history', { limit }));

/** Whether a taste profile in this state should be polled. */
export function shouldPollTaste(profile: TasteProfileDTO | undefined, pollWhileNone = false): boolean {
  if (!profile) return false;
  return profile.status === 'REBUILDING' || (pollWhileNone && profile.status === 'NONE');
}

type TasteProfileOptions = {
  enabled?: boolean;
  /** Also poll while there's no vector yet (right after onboarding completes, the first build is queued). */
  pollWhileNone?: boolean;
};

/**
 * The caller's taste profile: persona, top genres and every dimension's stated vs learned value.
 * While a rebuild is pending (`REBUILDING`) it re-reads every few seconds until the new vector lands.
 */
export function useTasteProfile({ enabled = true, pollWhileNone = false }: TasteProfileOptions = {}) {
  return useQuery({
    queryKey: queryKeys.tasteProfile,
    queryFn: fetchTasteProfile,
    enabled,
    refetchInterval: (query) => (shouldPollTaste(query.state.data, pollWhileNone) ? TASTE_POLL_MS : false),
  });
}

/** "How your taste changed": vector versions, newest first. */
export function useTasteHistory(limit?: number | null) {
  const l = limit ?? null;
  return useQuery({ queryKey: queryKeys.tasteHistory(l), queryFn: () => fetchTasteHistory(l) });
}

/**
 * A taste signal was just written: the server marked the vector dirty. Show that straight away (so
 * polling starts even before the refetch answers), then refetch the profile and history.
 */
export function markTasteRebuilding(qc: QueryClient): void {
  qc.setQueryData<TasteProfileDTO>(queryKeys.tasteProfile, (p) =>
    p && p.status !== 'NONE' ? { ...p, status: 'REBUILDING' } : p,
  );
  void qc.invalidateQueries({ queryKey: queryKeys.taste });
}
