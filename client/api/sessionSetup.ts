import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '../lib/queryKeys';
import type { SessionDTO, SessionFiltersDTO, Strategy, UserSummaryDTO } from '../types/api';
import { useMe } from './me';
import { fetchSession, fetchSessions } from './sessions';

/** What "Same crew again" fills in: the people (minus you), filters and strategy of a past session. */
export type SessionSetup = {
  sessionId: string;
  /** Friends from that session (you, and anyone who is no longer a friend, are left out). */
  members: UserSummaryDTO[];
  filters: SessionFiltersDTO;
  strategy: Strategy;
  createdAt: string;
};

/** The setup of `session` as seen by `myId`. */
export function setupFromSession(session: SessionDTO, myId: string | undefined): SessionSetup {
  return {
    sessionId: session.id,
    members: session.members
      .map((m) => m.user)
      .filter((u) => u.id !== myId && u.friendship === 'FRIENDS'),
    filters: session.filters ?? {},
    strategy: session.strategy,
    createdAt: session.createdAt,
  };
}

/** Sessions looked at for the newest Tonight one (the first history page). */
async function lastSetup(groupId: string | null, myId: string | undefined): Promise<SessionSetup | null> {
  const page = await fetchSessions(groupId);
  // Tonight reuses the last ad-hoc crew; a group reuses its own last session's filters.
  const last = page.items.find((s) => (groupId ? s.groupId === groupId : s.groupId == null));
  if (!last) return null;
  return setupFromSession(await fetchSession(last.id), myId);
}

/**
 * The newest past session to copy for "Same crew again": the caller's last Tonight session
 * (`groupId` null) or the group's last session. Comes from the server's session history, so it
 * follows you across devices; null when there is none.
 */
export function useLastSessionSetup(groupId: string | null | undefined, options: { enabled?: boolean } = {}) {
  const me = useMe();
  const myId = me.data?.id;
  const group = groupId ?? null;
  return useQuery({
    queryKey: [...queryKeys.sessions, 'lastSetup', group, myId ?? null] as const,
    queryFn: () => lastSetup(group, myId),
    enabled: !!myId && (options.enabled ?? true),
  });
}
