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
