import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { ApiError, apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type { PageDTO, RatingDTO, RecommendationDTO, UpsertRatingDTO } from '../types/api';
import { filterInfinite, mapInfinite, type InfinitePages } from './socialCache';
import { markTasteRebuilding } from './taste';

/** A rating's note is at most this long. */
export const RATING_NOTE_MAX = 1000;

export const fetchMyRatings = (cursor?: string | null) =>
  apiClient.get<PageDTO<RatingDTO>>(withQuery('/me/ratings', { cursor }));

/** The caller's rating of one movie, or null when they haven't rated it (404). */
export async function fetchMyRating(movieId: number): Promise<RatingDTO | null> {
  try {
    return await apiClient.get<RatingDTO>(`/me/ratings/${movieId}`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

/** Every rating of the caller's, newest first (calibration swipes included). Paged. */
export function useMyRatings() {
  return useInfiniteQuery({
    queryKey: queryKeys.myRatings,
    queryFn: ({ pageParam }) => fetchMyRatings(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

/** The caller's rating of `movieId`; `data` is null when unrated. */
export function useMyRating(movieId: number | null | undefined, options: { enabled?: boolean } = {}) {
  const valid = movieId != null && Number.isInteger(movieId) && movieId > 0;
  return useQuery({
    queryKey: queryKeys.myRating(movieId ?? 0),
    queryFn: () => fetchMyRating(movieId as number),
    enabled: valid && (options.enabled ?? true),
  });
}

/** Every taste signal moves the vector, so the taste profile and "For you" are refetched. */
function afterTasteSignal(qc: QueryClient, movieId: number): void {
  // A rated movie never shows in "For you"; drop it now rather than waiting for the refetch.
  qc.setQueryData<InfinitePages<RecommendationDTO>>(queryKeys.recommendations, (data) =>
    filterInfinite(data, (r) => r.movie.id !== movieId),
  );
  void qc.invalidateQueries({ queryKey: queryKeys.recommendations });
  markTasteRebuilding(qc);
}

type UpsertVars = { movieId: number; body: UpsertRatingDTO };

/** Quick rate: creates or replaces the caller's rating of one movie. */
export function useUpsertRating() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ movieId, body }: UpsertVars) => apiClient.put<RatingDTO>(`/ratings/${movieId}`, body),
    onSuccess: (rating, { movieId }) => {
      qc.setQueryData(queryKeys.myRating(movieId), rating);
      // Update in place if it's already listed; a new rating needs the list refetched to appear first.
      qc.setQueryData<InfinitePages<RatingDTO>>(queryKeys.myRatings, (data) =>
        mapInfinite(data, (r) => (r.movie.id === movieId ? rating : r)),
      );
      void qc.invalidateQueries({ queryKey: queryKeys.myRatings });
      afterTasteSignal(qc, movieId);
    },
  });
}

/** Removes the caller's rating of one movie (idempotent). */
export function useDeleteRating() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (movieId: number) => apiClient.del<void>(`/ratings/${movieId}`),
    onSuccess: (_void, movieId) => {
      qc.setQueryData(queryKeys.myRating(movieId), null);
      qc.setQueryData<InfinitePages<RatingDTO>>(queryKeys.myRatings, (data) =>
        filterInfinite(data, (r) => r.movie.id !== movieId),
      );
      afterTasteSignal(qc, movieId);
    },
  });
}
