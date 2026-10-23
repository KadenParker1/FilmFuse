import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import type { PendingRatingDTO } from '../types/api';

export const PENDING_RATINGS_PATH = '/me/pending-ratings';

export const fetchPendingRatings = () => apiClient.get<PendingRatingDTO[]>(PENDING_RATINGS_PATH);

/** "Rate what you watched": recent session picks the caller hasn't rated or dismissed (≤ 10, newest first). */
export function usePendingRatings() {
  return useQuery({ queryKey: queryKeys.pendingRatings, queryFn: fetchPendingRatings });
}

/** Drops `movieId` from the cached prompts (rated or dismissed). */
export function removePendingRating(qc: QueryClient, movieId: number): void {
  qc.setQueryData<PendingRatingDTO[]>(queryKeys.pendingRatings, (list) => list?.filter((p) => p.movie.id !== movieId));
}

/** "Not now" on a prompt: it's never shown again. Optimistic, restored on failure. */
export function useDismissPendingRating() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (movieId: number) => apiClient.post<void>(`${PENDING_RATINGS_PATH}/${movieId}/dismiss`),
    onMutate: async (movieId: number) => {
      await qc.cancelQueries({ queryKey: queryKeys.pendingRatings });
      const previous = qc.getQueryData<PendingRatingDTO[]>(queryKeys.pendingRatings);
      removePendingRating(qc, movieId);
      return { previous };
    },
    onError: (_error, _movieId, context) => {
      if (context?.previous) qc.setQueryData(queryKeys.pendingRatings, context.previous);
    },
  });
}

/** Undo of a dismissal: the prompt comes back (refetched, so it lands in its place). */
export function useUndismissPendingRating() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (movieId: number) => apiClient.del<void>(`${PENDING_RATINGS_PATH}/${movieId}/dismiss`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.pendingRatings }),
  });
}
