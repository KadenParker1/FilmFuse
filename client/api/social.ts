import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type {
  FriendDTO,
  FriendRequestDTO,
  FriendshipStatusDTO,
  FriendSuggestionDTO,
  PageDTO,
  PublicProfileDTO,
  RequestDirection,
  SendFriendRequestDTO,
  UpdateFriendDTO,
  UserSummaryDTO,
} from '../types/api';
import { applyFriendship, mapInfinite, removeFriend, removeRequest, type InfinitePages } from './socialCache';

const id = (userId: string) => encodeURIComponent(userId);

export const fetchFriends = (cursor?: string | null) =>
  apiClient.get<PageDTO<FriendDTO>>(withQuery('/friends', { cursor }));

export const fetchFriendRequests = (direction: RequestDirection) =>
  apiClient.get<FriendRequestDTO[]>(withQuery('/friends/requests', { direction }));

export const fetchSuggestions = (limit?: number | null) =>
  apiClient.get<FriendSuggestionDTO[]>(withQuery('/friends/suggestions', { limit }));

export const fetchBlocks = () => apiClient.get<UserSummaryDTO[]>('/blocks');

// ---- queries ------------------------------------------------------------------------------------

/** The caller's friends, close friends first. Paged. */
export function useFriends(options: { enabled?: boolean } = {}) {
  return useInfiniteQuery({
    queryKey: queryKeys.friends,
    queryFn: ({ pageParam }) => fetchFriends(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: options.enabled ?? true,
  });
}

/** Pending requests, newest first. INCOMING also drives the Social tab badge. */
export function useFriendRequests(direction: RequestDirection) {
  return useQuery({ queryKey: queryKeys.friendRequests(direction), queryFn: () => fetchFriendRequests(direction) });
}

/** Friends-of-friends, ranked by mutual friends. `limit` 1–50; omitted = server default (10). */
export function useSuggestions(limit?: number) {
  return useQuery({ queryKey: queryKeys.friendSuggestions(limit ?? null), queryFn: () => fetchSuggestions(limit) });
}

/** People the caller has blocked. */
export function useBlocks() {
  return useQuery({ queryKey: queryKeys.blocks, queryFn: fetchBlocks });
}

// ---- mutations ----------------------------------------------------------------------------------

/**
 * Sends a friend request (variable: the target's id). If they had already asked the caller, the
 * server accepts on the spot and answers FRIENDS.
 */
export function useSendFriendRequest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (targetUserId: string) => {
      const body: SendFriendRequestDTO = { targetUserId };
      return apiClient.post<FriendshipStatusDTO>('/friends/requests', body);
    },
    onSuccess: ({ userId, friendship }) => {
      applyFriendship(qc, userId, friendship);
      if (friendship === 'FRIENDS') {
        removeRequest(qc, 'INCOMING', userId);
        void qc.invalidateQueries({ queryKey: queryKeys.friends });
      } else {
        void qc.invalidateQueries({ queryKey: queryKeys.friendRequests('OUTGOING') });
      }
    },
  });
}

/** Accepts the pending request from `requesterId`. */
export function useAcceptRequest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (requesterId: string) =>
      apiClient.post<FriendshipStatusDTO>(`/friends/requests/${id(requesterId)}/accept`),
    onSuccess: ({ userId, friendship }) => {
      removeRequest(qc, 'INCOMING', userId);
      applyFriendship(qc, userId, friendship);
      void qc.invalidateQueries({ queryKey: queryKeys.friends });
      void qc.invalidateQueries({ queryKey: queryKeys.friendSuggestionsAll });
    },
  });
}

/** Declines the pending request from `requesterId`. */
export function useDeclineRequest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (requesterId: string) => apiClient.post<void>(`/friends/requests/${id(requesterId)}/decline`),
    onSuccess: (_, requesterId) => {
      removeRequest(qc, 'INCOMING', requesterId);
      applyFriendship(qc, requesterId, 'NONE');
    },
  });
}

/** Withdraws the caller's pending request to `targetUserId`. */
export function useCancelRequest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (targetUserId: string) => apiClient.del<void>(`/friends/requests/${id(targetUserId)}`),
    onSuccess: (_, targetUserId) => {
      removeRequest(qc, 'OUTGOING', targetUserId);
      applyFriendship(qc, targetUserId, 'NONE');
    },
  });
}

/** Ends the friendship with `userId`. */
export function useUnfriend() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => apiClient.del<void>(`/friends/${id(userId)}`),
    onSuccess: (_, userId) => {
      removeFriend(qc, userId);
      applyFriendship(qc, userId, 'NONE');
      void qc.invalidateQueries({ queryKey: queryKeys.friendSuggestionsAll });
    },
  });
}

type SetCloseFriendVars = { userId: string; closeFriend: boolean };

/**
 * Stars / unstars a friend. Optimistic: the friends list and their public profile flip immediately
 * and roll back if the server refuses. The row keeps its place until the list is next fetched.
 */
export function useSetCloseFriend() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, closeFriend }: SetCloseFriendVars) => {
      const body: UpdateFriendDTO = { closeFriend };
      return apiClient.patch<FriendDTO>(`/friends/${id(userId)}`, body);
    },
    onMutate: async ({ userId, closeFriend }: SetCloseFriendVars) => {
      const profileKey = queryKeys.publicProfile(userId);
      await Promise.all([
        qc.cancelQueries({ queryKey: queryKeys.friends }),
        qc.cancelQueries({ queryKey: profileKey }),
      ]);
      const previous = qc.getQueryData<InfinitePages<FriendDTO>>(queryKeys.friends);
      const previousProfile = qc.getQueryData<PublicProfileDTO>(profileKey);
      qc.setQueryData<InfinitePages<FriendDTO>>(queryKeys.friends, (data) =>
        mapInfinite(data, (f) => (f.id === userId ? { ...f, closeFriend } : f)),
      );
      qc.setQueryData<PublicProfileDTO>(profileKey, (p) => (p ? { ...p, closeFriend } : p));
      return { previous, previousProfile };
    },
    onError: (_error, { userId }, context) => {
      if (context?.previous) qc.setQueryData(queryKeys.friends, context.previous);
      if (context?.previousProfile) qc.setQueryData(queryKeys.publicProfile(userId), context.previousProfile);
    },
    onSuccess: (friend) => {
      qc.setQueryData<InfinitePages<FriendDTO>>(queryKeys.friends, (data) =>
        mapInfinite(data, (f) => (f.id === friend.id ? friend : f)),
      );
      qc.setQueryData<PublicProfileDTO>(queryKeys.publicProfile(friend.id), (p) =>
        p ? { ...p, closeFriend: friend.closeFriend } : p,
      );
    },
  });
}

/** Blocks `userId`: removes any friendship and pending requests both ways. */
export function useBlock() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => apiClient.post<void>(`/blocks/${id(userId)}`),
    onSuccess: (_, userId) => {
      removeFriend(qc, userId);
      removeRequest(qc, 'INCOMING', userId);
      removeRequest(qc, 'OUTGOING', userId);
      applyFriendship(qc, userId, 'BLOCKED');
      void qc.invalidateQueries({ queryKey: queryKeys.blocks });
    },
  });
}

/** Unblocks `userId` (they become a stranger again). */
export function useUnblock() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => apiClient.del<void>(`/blocks/${id(userId)}`),
    onSuccess: (_, userId) => {
      qc.setQueryData<UserSummaryDTO[]>(queryKeys.blocks, (list) => list?.filter((u) => u.id !== userId));
      applyFriendship(qc, userId, 'NONE');
      void qc.invalidateQueries({ queryKey: queryKeys.friendSuggestionsAll });
    },
  });
}
