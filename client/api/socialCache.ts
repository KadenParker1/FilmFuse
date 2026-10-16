import type { InfiniteData, QueryClient } from '@tanstack/react-query';
import { queryKeys } from '../lib/queryKeys';
import type {
  FriendDTO,
  FriendRequestDTO,
  FriendshipState,
  FriendSuggestionDTO,
  PageDTO,
  PublicProfileDTO,
  RequestDirection,
  UserSummaryDTO,
} from '../types/api';

/**
 * Targeted cache edits shared by the social mutations, so a change made on one screen (a profile,
 * a search row, the Friends tab) shows everywhere without refetching every list.
 */

export type InfinitePages<T> = InfiniteData<PageDTO<T>, string | null>;

/** Maps every item of every loaded page; undefined stays undefined (nothing cached yet). */
export function mapInfinite<T>(data: InfinitePages<T> | undefined, fn: (item: T) => T): InfinitePages<T> | undefined {
  if (!data) return data;
  return { ...data, pages: data.pages.map((p) => ({ ...p, items: p.items.map(fn) })) };
}

/** Drops matching items from every loaded page. */
export function filterInfinite<T>(
  data: InfinitePages<T> | undefined,
  keep: (item: T) => boolean,
): InfinitePages<T> | undefined {
  if (!data) return data;
  return { ...data, pages: data.pages.map((p) => ({ ...p, items: p.items.filter(keep) })) };
}

/**
 * Records the caller's new relationship with `userId` wherever it's shown: the public profile
 * (adjusting its friend count when a friendship starts or ends), search results, and suggestions
 * (anyone no longer a plain stranger drops out of them).
 */
export function applyFriendship(qc: QueryClient, userId: string, friendship: FriendshipState): void {
  qc.setQueryData<PublicProfileDTO>(queryKeys.publicProfile(userId), (p) => {
    if (!p) return p;
    const delta =
      p.friendship !== 'FRIENDS' && friendship === 'FRIENDS'
        ? 1
        : p.friendship === 'FRIENDS' && friendship !== 'FRIENDS'
          ? -1
          : 0;
    // Close-friend flags only exist between friends; ending the friendship clears it.
    return {
      ...p,
      friendship,
      closeFriend: friendship === 'FRIENDS' && p.closeFriend,
      friendCount: Math.max(0, p.friendCount + delta),
    };
  });
  qc.setQueriesData<InfinitePages<UserSummaryDTO>>({ queryKey: queryKeys.userSearchAll }, (data) =>
    mapInfinite(data, (u) => (u.id === userId ? { ...u, friendship } : u)),
  );
  if (friendship !== 'NONE') {
    qc.setQueriesData<FriendSuggestionDTO[]>({ queryKey: queryKeys.friendSuggestionsAll }, (list) =>
      list?.filter((s) => s.user.id !== userId),
    );
  }
}

/** Removes the pending request with `userId` from one direction's list. */
export function removeRequest(qc: QueryClient, direction: RequestDirection, userId: string): void {
  qc.setQueryData<FriendRequestDTO[]>(queryKeys.friendRequests(direction), (list) =>
    list?.filter((r) => r.user.id !== userId),
  );
}

/** Removes `userId` from the loaded friends pages. */
export function removeFriend(qc: QueryClient, userId: string): void {
  qc.setQueryData<InfinitePages<FriendDTO>>(queryKeys.friends, (data) => filterInfinite(data, (f) => f.id !== userId));
}
