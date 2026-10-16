/**
 * Up to two initials for an avatar fallback: first letters of the first and last words of the
 * display name, else the first letter of the username, else "?".
 */
export function initialsFor(displayName?: string | null, username?: string | null): string {
  const words = (displayName ?? '').trim().split(/\s+/).filter(Boolean);
  const first = words[0];
  const last = words.length > 1 ? words[words.length - 1] : undefined;
  if (first) return (Array.from(first)[0]! + (last ? Array.from(last)[0]! : '')).toUpperCase();
  const u = (username ?? '').trim().replace(/^@/, '');
  if (u) return Array.from(u)[0]!.toUpperCase();
  return '?';
}
