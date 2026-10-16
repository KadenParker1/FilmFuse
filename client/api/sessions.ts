import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type {
  CastVoteDTO,
  CreateSessionDTO,
  PageDTO,
  PickMovieDTO,
  SessionDTO,
  SessionResultDTO,
  SessionSummaryDTO,
  Vote,
} from '../types/api';

const id = (value: string) => encodeURIComponent(value);

export const fetchSession = (sessionId: string) => apiClient.get<SessionDTO>(`/sessions/${id(sessionId)}`);

export const fetchSessions = (groupId?: string | null, cursor?: string | null) =>
  apiClient.get<PageDTO<SessionSummaryDTO>>(withQuery('/sessions', { groupId, cursor }));

/**
 * The caller's vote on one result changes from `result.myVote` to `next` (null = cleared). The
 * tallies move with it. Taking back a veto leaves `vetoed` alone — someone else may have vetoed too;
 * the server's answer settles it.
 */
export function applyVote(result: SessionResultDTO, next: Vote | null): SessionResultDTO {
  const votes = { ...result.votes };
  if (result.myVote === 'UP') votes.up = Math.max(0, votes.up - 1);
  if (result.myVote === 'DOWN') votes.down = Math.max(0, votes.down - 1);
  if (next === 'UP') votes.up += 1;
  if (next === 'DOWN') votes.down += 1;
  if (next === 'VETO') votes.vetoed = true;
  return { ...result, votes, myVote: next };
}

/** A new session (or a change to one) moves history lists and the group's "last session". */
function invalidateHistory(qc: QueryClient, groupId: string | null): void {
  void qc.invalidateQueries({ queryKey: queryKeys.sessionListAll });
  void qc.invalidateQueries({ queryKey: queryKeys.groupList });
  if (groupId) void qc.invalidateQueries({ queryKey: queryKeys.group(groupId) });
}

// ---- queries ------------------------------------------------------------------------------------

/** One session with its ranked results. Live updates come from useSessionLive. */
export function useSession(sessionId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.session(sessionId ?? ''),
    queryFn: () => fetchSession(sessionId as string),
    enabled: !!sessionId,
  });
}

/** Session history, newest first; a group's only when `groupId` is given. Paged. */
export function useSessions(groupId?: string | null) {
  const group = groupId ?? null;
  return useInfiniteQuery({
    queryKey: queryKeys.sessionList(group),
    queryFn: ({ pageParam }) => fetchSessions(group, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

// ---- mutations ----------------------------------------------------------------------------------

/** Starts a session for a saved group (`groupId`) or an ad-hoc set of friends (`memberIds`). */
export function useCreateSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateSessionDTO) => apiClient.post<SessionDTO>('/sessions', body),
    onSuccess: (session) => {
      qc.setQueryData(queryKeys.session(session.id), session);
      invalidateHistory(qc, session.groupId);
    },
  });
}

type VoteContext = { previous: SessionDTO | undefined };

/** Optimistically rewrites one result's vote; returns the snapshot to roll back to. */
async function optimisticVote(
  qc: QueryClient,
  sessionId: string,
  movieId: number,
  next: Vote | null,
): Promise<VoteContext> {
  const key = queryKeys.session(sessionId);
  await qc.cancelQueries({ queryKey: key });
  const previous = qc.getQueryData<SessionDTO>(key);
  qc.setQueryData<SessionDTO>(key, (s) =>
    s ? { ...s, results: s.results.map((r) => (r.movie.id === movieId ? applyVote(r, next) : r)) } : s,
  );
  return { previous };
}

function rollback(qc: QueryClient, sessionId: string, context: VoteContext | undefined): void {
  if (context?.previous) qc.setQueryData(queryKeys.session(sessionId), context.previous);
}

type CastVoteVars = { sessionId: string; movieId: number; vote: Vote };

/** Up / Down / Veto one result. The tally and highlight change at once and roll back on failure. */
export function useCastVote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ sessionId, movieId, vote }: CastVoteVars) => {
      const body: CastVoteDTO = { vote };
      return apiClient.put<SessionDTO>(`/sessions/${id(sessionId)}/votes/${movieId}`, body);
    },
    onMutate: ({ sessionId, movieId, vote }: CastVoteVars) => optimisticVote(qc, sessionId, movieId, vote),
    onError: (_error, { sessionId }, context) => rollback(qc, sessionId, context),
    onSuccess: (session) => qc.setQueryData(queryKeys.session(session.id), session),
  });
}

type ClearVoteVars = { sessionId: string; movieId: number };

/** Takes back the caller's vote on one result (optimistic, like useCastVote). */
export function useClearVote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ sessionId, movieId }: ClearVoteVars) =>
      apiClient.del<SessionDTO>(`/sessions/${id(sessionId)}/votes/${movieId}`),
    onMutate: ({ sessionId, movieId }: ClearVoteVars) => optimisticVote(qc, sessionId, movieId, null),
    onError: (_error, { sessionId }, context) => rollback(qc, sessionId, context),
    onSuccess: (session) => qc.setQueryData(queryKeys.session(session.id), session),
  });
}

/** "More options": appends freshly ranked results (never vetoed or already shown ones). */
export function useRefreshSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (sessionId: string) => apiClient.post<SessionDTO>(`/sessions/${id(sessionId)}/refresh`),
    onSuccess: (session) => qc.setQueryData(queryKeys.session(session.id), session),
  });
}

type PickVars = { sessionId: string; movieId: number };

/** Decides the session on one movie (any member may). */
export function usePickMovie() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ sessionId, movieId }: PickVars) => {
      const body: PickMovieDTO = { movieId };
      return apiClient.post<SessionDTO>(`/sessions/${id(sessionId)}/pick`, body);
    },
    onSuccess: (session) => {
      qc.setQueryData(queryKeys.session(session.id), session);
      invalidateHistory(qc, session.groupId);
    },
  });
}
