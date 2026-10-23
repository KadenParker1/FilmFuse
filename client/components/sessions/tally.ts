import type { SessionResultDTO } from '../../types/api';

/**
 * The live vote tally drawn over a session's results. Display order never changes - results stay in
 * the ML rank order (the owner's algorithm); this only decides which card is highlighted as the
 * leader and what "Pick the leader" picks.
 *
 * Leader rule (deterministic): among results that are not vetoed and have at least one thumbs-up,
 * the most ups wins, then the fewest downs, then the better (lower) ML rank. Results level with the
 * leader on ups and downs are "tied"; the ML rank still breaks the tie for the shortcut, and the UI
 * says so.
 */
export type Tally = {
  /** The result "Pick the leader" picks; null while nothing has a thumbs-up. */
  leader: SessionResultDTO | null;
  /** Every result level with the leader on ups and downs, leader first (just the leader when there is no tie). */
  tied: SessionResultDTO[];
  isTie: boolean;
  /** Movie ids every member gave a thumbs-up (not vetoed). */
  everyoneLikes: Set<number>;
  /** Every member has voted on something. */
  everyoneVoted: boolean;
};

/** Most up, then fewest down, then ML rank. */
export function compareByVotes(a: SessionResultDTO, b: SessionResultDTO): number {
  return b.votes.up - a.votes.up || a.votes.down - b.votes.down || a.rank - b.rank;
}

export function computeTally(results: SessionResultDTO[], memberCount: number, votedCount: number): Tally {
  const live = results.filter((r) => !r.votes.vetoed);
  const ranked = live.filter((r) => r.votes.up > 0).sort(compareByVotes);
  const leader = ranked[0] ?? null;
  const tied = leader
    ? ranked.filter((r) => r.votes.up === leader.votes.up && r.votes.down === leader.votes.down)
    : [];
  const everyoneLikes = new Set(
    memberCount > 0 ? live.filter((r) => r.votes.up >= memberCount).map((r) => r.movie.id) : [],
  );
  return {
    leader,
    tied,
    isTie: tied.length > 1,
    everyoneLikes,
    everyoneVoted: memberCount > 0 && votedCount >= memberCount,
  };
}

const votesText = (r: SessionResultDTO) => `${r.votes.up} up, ${r.votes.down} down`;

/** "A and B" / "A, B and C". */
function joinTitles(titles: string[]): string {
  if (titles.length <= 1) return titles[0] ?? '';
  return `${titles.slice(0, -1).join(', ')} and ${titles[titles.length - 1]}`;
}

/** One sentence for screen readers (the live region); changes only when the standings do. */
export function tallySummary(tally: Tally): string {
  const parts: string[] = [];
  if (!tally.leader) {
    parts.push('No thumbs-up yet.');
  } else if (tally.isTie) {
    parts.push(`Tied: ${joinTitles(tally.tied.map((r) => r.movie.title))}, ${votesText(tally.leader)} each.`);
  } else {
    parts.push(`Leading: ${tally.leader.movie.title}, ${votesText(tally.leader)}.`);
  }
  if (tally.leader && tally.everyoneLikes.has(tally.leader.movie.id)) parts.push('Everyone likes it.');
  if (tally.everyoneVoted) parts.push('Everyone’s voted.');
  return parts.join(' ');
}

/** What the "Pick the leader" button and the leader card say about a tie. */
export function leaderNote(tally: Tally): string | null {
  if (!tally.leader) return null;
  if (tally.isTie) return `Tied with ${tally.tied.length - 1} other${tally.tied.length > 2 ? 's' : ''} — the higher-ranked pick goes first`;
  return null;
}

/** "18h left" / "45m left" / "Closing" for an OPEN session's closesAt. */
export function timeLeft(closesAt: string, now: Date = new Date()): string {
  const ms = new Date(closesAt).getTime() - now.getTime();
  if (!Number.isFinite(ms) || ms <= 60_000) return 'Closing';
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 60) return `${minutes}m left`;
  return `${Math.floor(minutes / 60)}h left`;
}
