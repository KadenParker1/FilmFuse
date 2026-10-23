import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
  type QueryKey,
} from '@tanstack/react-query';
import { ApiError, apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type { FeedItemDTO, PageDTO, RatingDTO, RecommendationDTO, UpsertRatingDTO } from '../types/api';
import { invalidateFeedsAfterRating } from './feed';
import { removePendingRating } from './pendingRatings';
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

/**
 * Every taste signal moves the vector, so the taste profile and "For you" are refetched; the
 * rating's feed lists (your feed, the movie's ratings, your own ratings) are refetched too.
 */
function afterTasteSignal(qc: QueryClient, movieId: number): void {
  invalidateFeedsAfterRating(qc, movieId);
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
    mutationFn: async ({ movieId, body }: UpsertVars) => {
      // Re-rating wins over a removal still waiting for its undo window (it is dropped, never sent),
      // and waits for one already on its way so the PUT lands after the DELETE.
      cancelPendingRemoval(movieId);
      await removalsInFlight.get(movieId)?.catch(() => {});
      return apiClient.put<RatingDTO>(`/ratings/${movieId}`, body);
    },
    onSuccess: (rating, { movieId }) => {
      qc.setQueryData(queryKeys.myRating(movieId), rating);
      // Update in place if it's already listed; a new rating needs the list refetched to appear first.
      qc.setQueryData<InfinitePages<RatingDTO>>(queryKeys.myRatings, (data) =>
        mapInfinite(data, (r) => (r.movie.id === movieId ? rating : r)),
      );
      void qc.invalidateQueries({ queryKey: queryKeys.myRatings });
      // A rated movie is never prompted for again ("Rate what you watched").
      removePendingRating(qc, movieId);
      void qc.invalidateQueries({ queryKey: queryKeys.pendingRatings });
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

// ---- deferred removal ("Remove rating" with Undo) ------------------------------------------------
//
// DELETE /ratings/{movieId} is final (likes and comments go with the row), so "Remove rating" only
// hides the rating in the caches at first. The DELETE is sent when the undo window closes
// (commitRatingRemoval); Undo puts the caches back with no request at all, so the row, its id,
// likes, comments and dates are untouched. Re-rating the movie meanwhile drops the pending removal.

type PendingRemoval = { rating: RatingDTO; snapshot: [QueryKey, unknown][] };
const pendingRemovals = new Map<number, PendingRemoval>();
const removalsInFlight = new Map<number, Promise<void>>();

/** Every cached query the rating shows up in: your rating of the movie, your list, every feed list. */
function snapshotRatingCaches(qc: QueryClient, movieId: number): [QueryKey, unknown][] {
  return [
    ...qc.getQueriesData({ queryKey: queryKeys.myRating(movieId), exact: true }),
    ...qc.getQueriesData({ queryKey: queryKeys.myRatings, exact: true }),
    ...qc.getQueriesData({ queryKey: queryKeys.feed }),
  ];
}

/** Whether `movieId`'s rating is hidden and waiting for its undo window to close. */
export function isRatingRemovalPending(movieId: number): boolean {
  return pendingRemovals.has(movieId);
}

/** Hides `rating` everywhere it's cached (optimistic) and marks its removal pending. */
export function hideRatingForRemoval(qc: QueryClient, rating: RatingDTO): void {
  const movieId = rating.movie.id;
  // A second removal of the same movie keeps the first snapshot (the one with the rating in it).
  const snapshot = pendingRemovals.get(movieId)?.snapshot ?? snapshotRatingCaches(qc, movieId);
  pendingRemovals.set(movieId, { rating, snapshot });
  void qc.cancelQueries({ queryKey: queryKeys.feed });
  void qc.cancelQueries({ queryKey: queryKeys.myRatings });
  qc.setQueryData(queryKeys.myRating(movieId), null);
  qc.setQueryData<InfinitePages<RatingDTO>>(queryKeys.myRatings, (data) =>
    filterInfinite(data, (r) => r.movie.id !== movieId),
  );
  qc.setQueriesData<InfinitePages<FeedItemDTO>>({ queryKey: queryKeys.feed }, (data) =>
    data && 'pages' in data ? filterInfinite(data, (item) => item.id !== rating.id) : data,
  );
}

function restoreSnapshot(qc: QueryClient, snapshot: [QueryKey, unknown][]): void {
  for (const [key, data] of snapshot) qc.setQueryData(key, data);
}

/** Undo: the hidden rating comes back in every cache, no request. False when nothing was pending. */
export function undoRatingRemoval(qc: QueryClient, movieId: number): boolean {
  const pending = pendingRemovals.get(movieId);
  if (!pending) return false;
  pendingRemovals.delete(movieId);
  restoreSnapshot(qc, pending.snapshot);
  return true;
}

/** Drops a pending removal without sending or restoring anything (the movie is being re-rated). */
export function cancelPendingRemoval(movieId: number): boolean {
  return pendingRemovals.delete(movieId);
}

/**
 * The undo window closed: sends the DELETE (`keepalive` lets it outlive a closing web page).
 * Resolves false when nothing was pending (undone or re-rated). On failure the rating comes back in
 * the caches and the error is rethrown.
 */
export async function commitRatingRemoval(
  qc: QueryClient,
  movieId: number,
  options: { keepalive?: boolean; onSend?: () => void } = {},
): Promise<boolean> {
  const pending = pendingRemovals.get(movieId);
  if (!pending) return false;
  pendingRemovals.delete(movieId);
  options.onSend?.();
  // The taste starts rebuilding now that the signal is real.
  markTasteRebuilding(qc);
  const sent = apiClient.del<void>(`/ratings/${movieId}`, { keepalive: options.keepalive });
  removalsInFlight.set(movieId, sent);
  try {
    await sent;
  } catch (e) {
    restoreSnapshot(qc, pending.snapshot);
    void qc.invalidateQueries({ queryKey: queryKeys.taste });
    throw e;
  } finally {
    if (removalsInFlight.get(movieId) === sent) removalsInFlight.delete(movieId);
  }
  afterTasteSignal(qc, movieId);
  // Unrated again: it may be prompted for ("Rate what you watched") or recommended once more.
  void qc.invalidateQueries({ queryKey: queryKeys.pendingRatings });
  return true;
}

/** Tests only: forget every pending removal. */
export function resetPendingRemovalsForTests(): void {
  pendingRemovals.clear();
  removalsInFlight.clear();
}
