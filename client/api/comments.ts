import { useInfiniteQuery, useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type { CommentDTO, CreateCommentDTO, PageDTO, ProfileResponseDTO } from '../types/api';
import { updateFeedItem } from './feed';
import { filterInfinite, mapInfinite, type InfinitePages } from './socialCache';

/** A comment is 1–500 characters after trimming. */
export const COMMENT_MAX = 500;

/** Ids of optimistic comments that the server hasn't confirmed yet. */
export const PENDING_COMMENT_PREFIX = 'pending-';
export const isPendingComment = (comment: Pick<CommentDTO, 'id'>) => comment.id.startsWith(PENDING_COMMENT_PREFIX);

const enc = encodeURIComponent;

export const fetchComments = (ratingId: string, cursor?: string | null) =>
  apiClient.get<PageDTO<CommentDTO>>(withQuery(`/ratings/${enc(ratingId)}/comments`, { cursor }));

/** A rating's comments, oldest first, 30 a page. 404 = the rating isn't visible to the caller. */
export function useComments(ratingId: string | null | undefined) {
  return useInfiniteQuery({
    queryKey: queryKeys.comments(ratingId ?? ''),
    queryFn: ({ pageParam }) => fetchComments(ratingId as string, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: !!ratingId,
  });
}

/**
 * The loaded comments in order. A comment the caller just posted is appended to the last loaded
 * page even when older pages remain; once the real page holding it loads, the later copy wins.
 */
export function flattenComments(data: { pages: PageDTO<CommentDTO>[] } | undefined): CommentDTO[] {
  const all = data?.pages.flatMap((p) => p.items) ?? [];
  const lastIndex = new Map(all.map((c, i) => [c.id, i]));
  return all.filter((c, i) => lastIndex.get(c.id) === i);
}

/** Moves rating `ratingId`'s `commentCount` by `delta` everywhere it's cached (never below 0). */
export function applyCommentCount(qc: QueryClient, ratingId: string, delta: number): void {
  updateFeedItem(qc, ratingId, (item) => ({ ...item, commentCount: Math.max(0, item.commentCount + delta) }));
}

function appendComment(qc: QueryClient, ratingId: string, comment: CommentDTO): void {
  qc.setQueryData<InfinitePages<CommentDTO>>(queryKeys.comments(ratingId), (data) => {
    const last = data?.pages[data.pages.length - 1];
    if (!data || !last) return data;
    return { ...data, pages: [...data.pages.slice(0, -1), { ...last, items: [...last.items, comment] }] };
  });
}

function replaceComment(qc: QueryClient, ratingId: string, id: string, next: CommentDTO): void {
  qc.setQueryData<InfinitePages<CommentDTO>>(queryKeys.comments(ratingId), (data) =>
    mapInfinite(data, (c) => (c.id === id ? next : c)),
  );
}

function removeComment(qc: QueryClient, ratingId: string, id: string): void {
  qc.setQueryData<InfinitePages<CommentDTO>>(queryKeys.comments(ratingId), (data) =>
    filterInfinite(data, (c) => c.id !== id),
  );
}

let pendingSeq = 0;

/** The optimistic stand-in shown while a new comment is being posted. */
function pendingComment(qc: QueryClient, ratingId: string, body: string): CommentDTO {
  const me = qc.getQueryData<ProfileResponseDTO>(queryKeys.me);
  pendingSeq += 1;
  return {
    id: `${PENDING_COMMENT_PREFIX}${pendingSeq}`,
    ratingId,
    author: {
      id: me?.id ?? '',
      username: me?.username ?? null,
      displayName: me?.displayName ?? null,
      profileImageUrl: me?.profileImageUrl ?? null,
      friendship: 'NONE',
    },
    body,
    createdAt: new Date().toISOString(),
    mine: true,
    canDelete: false,
  };
}

/**
 * Posts a comment (body trimmed). Optimistic: it shows at the end of the list and the rating's
 * comment count goes up everywhere at once; both are undone if the server refuses.
 */
export function useCreateComment(ratingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: string) => {
      const dto: CreateCommentDTO = { body: body.trim() };
      return apiClient.post<CommentDTO>(`/ratings/${enc(ratingId)}/comments`, dto);
    },
    onMutate: async (body: string) => {
      await qc.cancelQueries({ queryKey: queryKeys.comments(ratingId) });
      const pending = pendingComment(qc, ratingId, body.trim());
      appendComment(qc, ratingId, pending);
      applyCommentCount(qc, ratingId, 1);
      return { pendingId: pending.id };
    },
    onSuccess: (comment, _body, context) => {
      if (context) replaceComment(qc, ratingId, context.pendingId, comment);
    },
    onError: (_error, _body, context) => {
      if (!context) return;
      removeComment(qc, ratingId, context.pendingId);
      applyCommentCount(qc, ratingId, -1);
    },
  });
}

/**
 * Deletes a comment (its author or the rating's author may). Optimistic: it disappears and the
 * count goes down at once; restored if the server refuses.
 */
export function useDeleteComment(ratingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (commentId: string) => apiClient.del<void>(`/comments/${enc(commentId)}`),
    onMutate: async (commentId: string) => {
      await qc.cancelQueries({ queryKey: queryKeys.comments(ratingId) });
      const previous = qc.getQueryData<InfinitePages<CommentDTO>>(queryKeys.comments(ratingId));
      const wasListed = flattenComments(previous).some((c) => c.id === commentId);
      removeComment(qc, ratingId, commentId);
      applyCommentCount(qc, ratingId, -1);
      return { previous, wasListed };
    },
    onError: (_error, _commentId, context) => {
      if (!context) return;
      if (context.wasListed) qc.setQueryData(queryKeys.comments(ratingId), context.previous);
      applyCommentCount(qc, ratingId, 1);
    },
  });
}
