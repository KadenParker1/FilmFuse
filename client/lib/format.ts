/** "2h 46m" / "52m" / "2h"; null for unknown or non-positive runtimes. */
export function formatRuntime(minutes: number | null | undefined): string | null {
  if (minutes == null || !Number.isFinite(minutes) || minutes <= 0) return null;
  const h = Math.floor(minutes / 60);
  const m = Math.round(minutes % 60);
  return h > 0 ? `${h}h${m > 0 ? ` ${m}m` : ''}` : `${m}m`;
}

/** "1 friend" / "3 friends"; `plural` defaults to singular + "s". */
export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

/** Tab-bar badge for a count: nothing at zero, "9+" past nine. */
export function badgeCount(count: number | null | undefined): number | string | undefined {
  if (count == null || !Number.isFinite(count) || count <= 0) return undefined;
  return count > 9 ? '9+' : count;
}

/** How to show a person: display name, else @username, else "New member" (no username picked yet). */
export function personName(user: { displayName?: string | null; username?: string | null }): string {
  return user.displayName?.trim() || (user.username ? `@${user.username}` : 'New member');
}

/** "just now" / "5 minutes ago" / "3 hours ago" / "2 days ago" / a short date past a week. */
export function timeAgo(iso: string | null | undefined, now: Date = new Date()): string | null {
  if (!iso) return null;
  const then = new Date(iso);
  const ms = now.getTime() - then.getTime();
  if (!Number.isFinite(ms)) return null;
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${pluralize(minutes, 'minute')} ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${pluralize(hours, 'hour')} ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${pluralize(days, 'day')} ago`;
  return then.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}
