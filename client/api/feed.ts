import { useInfiniteQuery, useMutation, useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type { FeedItemDTO, FeedScope, PageDTO, ProfileResponseDTO } from '../types/api';
import { mapInfinite, type InfinitePages } from './socialCache';

export const fetchFeed = (scope: FeedScope, cursor?: string | null) =>
  apiClient.get<PageDTO<FeedItemDTO>>(withQuery('/feed', { scope, cursor }));

export const fetchMovieRatings = (movieId: number, scope: FeedScope, cursor?: string | null) =>
  apiClient.get<PageDTO<FeedItemDTO>>(withQuery(`/movies/${movieId}/ratings`, { scope, cursor }));

export const fetchUserRatings = (userId: string, cursor?: string | null) =>
  apiClient.get<PageDTO<FeedItemDTO>>(withQuery(`/users/${encodeURIComponent(userId)}/ratings`, { cursor }));

const validMovieId = (id: number | null | undefined): id is number => id != null && Number.isInteger(id) && id > 0;

/** The ratings feed, newest first. FRIENDS = friends' and your own ratings; EVERYONE = public ones. */
export function useFeed(scope: FeedScope) {
  return useInfiniteQuery({
    queryKey: queryKeys.feedList(scope),
    queryFn: ({ pageParam }) => fetchFeed(scope, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

/** One movie's visible ratings in a scope. */
export function useMovieRatings(movieId: number | null | undefined, scope: FeedScope) {
  return useInfiniteQuery({
    queryKey: queryKeys.movieRatings(movieId ?? 0, scope),
    queryFn: ({ pageParam }) => fetchMovieRatings(movieId as number, scope, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: validMovieId(movieId),
  });
}

/** Someone's ratings that the caller may see (your own include private ones). */
export function useUserRatings(userId: string | null | undefined) {
  return useInfiniteQuery({
    queryKey: queryKeys.userRatings(userId ?? ''),
    queryFn: ({ pageParam }) => fetchUserRatings(userId as string, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: !!userId,
  });
}

/**
 * Sets `likedByMe` on rating `ratingId` in every cached feed-item list (both feeds, every movie's
 * ratings, every person's ratings), adjusting `likeCount` only when the flag actually changes.
 */
export function applyLike(qc: QueryClient, ratingId: string, liked: boolean): void {
  qc.setQueriesData<InfinitePages<FeedItemDTO>>({ queryKey: queryKeys.feed }, (data) =>
    mapInfinite(data, (item) =>
      item.id !== ratingId || item.likedByMe === liked
        ? item
        : { ...item, likedByMe: liked, likeCount: Math.max(0, item.likeCount + (liked ? 1 : -1)) },
    ),
  );
}

type Snapshot = [QueryKey, InfinitePages<FeedItemDTO> | undefined][];

function useLikeMutation(liked: boolean) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ratingId: string) =>
      liked
        ? apiClient.post<void>(`/ratings/${encodeURIComponent(ratingId)}/like`)
        : apiClient.del<void>(`/ratings/${encodeURIComponent(ratingId)}/like`),
    onMutate: async (ratingId: string) => {
      // A refetch landing mid-mutation would overwrite the optimistic flag with stale data.
      await qc.cancelQueries({ queryKey: queryKeys.feed });
      const previous: Snapshot = qc.getQueriesData<InfinitePages<FeedItemDTO>>({ queryKey: queryKeys.feed });
      applyLike(qc, ratingId, liked);
      return { previous };
    },
    onError: (_error, _ratingId, context) => {
      context?.previous.forEach(([key, data]) => qc.setQueryData(key, data));
    },
  });
}

/** Likes a rating (idempotent); optimistic everywhere it's listed, rolled back on failure. */
export const useLikeRating = () => useLikeMutation(true);

/** Removes the caller's like (idempotent); optimistic everywhere it's listed, rolled back on failure. */
export const useUnlikeRating = () => useLikeMutation(false);

/**
 * After the caller saves or deletes a rating of `movieId`: refetch the lists it shows up in — the
 * FRIENDS feed (it includes your own), both scopes of that movie's ratings, your own ratings list,
 * and the EVERYONE feed (the rating may be, or have been, public).
 */
export function invalidateFeedsAfterRating(qc: QueryClient, movieId: number): void {
  void qc.invalidateQueries({ queryKey: queryKeys.feedList('FRIENDS') });
  void qc.invalidateQueries({ queryKey: queryKeys.feedList('EVERYONE') });
  void qc.invalidateQueries({ queryKey: queryKeys.movieRatingsFor(movieId) });
  const me = qc.getQueryData<ProfileResponseDTO>(queryKeys.me);
  if (me) void qc.invalidateQueries({ queryKey: queryKeys.userRatings(me.id) });
}
