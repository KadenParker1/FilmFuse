import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type { PageDTO, PublicProfileDTO, UserSummaryDTO } from '../types/api';

/** People search takes 2–50 chars server-side; anything else is never sent. */
export const USER_SEARCH_MIN_LENGTH = 2;
export const USER_SEARCH_MAX_LENGTH = 50;

export const fetchUserSearch = (q: string, cursor?: string | null) =>
  apiClient.get<PageDTO<UserSummaryDTO>>(withQuery('/users/search', { q, cursor }));

export const fetchPublicProfile = (id: string) => apiClient.get<PublicProfileDTO>(`/users/${encodeURIComponent(id)}`);

/** Whether a (trimmed) query is long enough, and short enough, to search for. */
export const isSearchableUserQuery = (q: string) =>
  q.length >= USER_SEARCH_MIN_LENGTH && q.length <= USER_SEARCH_MAX_LENGTH;

/**
 * Paged people search by username / display-name prefix. Pass an already-debounced query; it's
 * disabled until it has at least two characters.
 */
export function useUserSearch(q: string) {
  const query = q.trim();
  return useInfiniteQuery({
    queryKey: queryKeys.userSearch(query),
    queryFn: ({ pageParam }) => fetchUserSearch(query, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: isSearchableUserQuery(query),
  });
}

/** Someone's public profile, including the caller's friendship state with them. 404 = not available. */
export function usePublicProfile(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.publicProfile(id ?? ''),
    queryFn: () => fetchPublicProfile(id!),
    enabled: !!id,
  });
}
