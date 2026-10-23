import { useGroups } from './groups';
import { unvotedCount, useLiveSessions } from './sessions';

/**
 * The Groups tab badge: open sessions the caller is in but hasn't voted in, plus pending group
 * invites. Reuses the "Live now" and group-list queries, so the Groups tab adds no requests.
 */
export function useGroupsBadgeCount(): number {
  const live = useLiveSessions();
  const groups = useGroups();
  const invites = (groups.data ?? []).filter((g) => g.memberStatus === 'INVITED').length;
  return unvotedCount(live.data?.items) + invites;
}
