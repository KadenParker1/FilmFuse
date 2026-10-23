/**
 * Which in-app links may be remembered across sign-in: an invite (`/invite/<token>`), a profile
 * (`/user/<id>`, e.g. from a friend-request email), a rating (`/ratings/<id>`, from a comment
 * email), a session (`/sessions/<uuid>`, from a "session started" email or a shared link) or a group
 * (`/groups/<uuid>`, from a group invite). Anything else is ignored, so a tampered storage value or
 * link can't send someone to an arbitrary route, let alone another site.
 */
const PENDING_LINK_RE = /^\/(invite|user|ratings)\/[^/?#\s]+$/;

/** Session and group ids are UUIDs; nothing else is accepted for them. */
const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
/** Screens behind the sign-in guard (they can't save themselves while signed out). */
const GUARDED_LINK_RE = new RegExp(`^/(sessions|groups)/${UUID}$`);

/** A session or group link: only reachable signed in, so the root layout saves it (see lib/launchLink). */
export const isGuardedLinkPath = (path: string | null | undefined): path is string =>
  typeof path === 'string' && GUARDED_LINK_RE.test(path);

export const isPendingLinkPath = (path: string | null | undefined): path is string =>
  typeof path === 'string' && (PENDING_LINK_RE.test(path) || GUARDED_LINK_RE.test(path));

export const invitePath = (token: string) => `/invite/${encodeURIComponent(token)}`;
export const profilePath = (userId: string) => `/user/${encodeURIComponent(userId)}`;
export const ratingPath = (ratingId: string) => `/ratings/${encodeURIComponent(ratingId)}`;

/**
 * The in-app path of a URL the app was opened with, or null: `https://host/sessions/x?y` →
 * `/sessions/x`, `filmfuse://sessions/x` → `/sessions/x`, Expo Go's `exp://host:8081/--/sessions/x`
 * → `/sessions/x`. Query and fragment are dropped; a trailing slash is ignored. Callers still check
 * the result against the allow-list.
 */
export function pathFromUrl(url: string | null | undefined): string | null {
  if (typeof url !== 'string' || !url) return null;
  const match = /^([a-z][a-z0-9+.-]*):\/\/(.*)$/i.exec(url);
  if (!match) return null;
  const protocol = match[1] ?? '';
  const after = match[2] ?? '';
  let rest: string;
  const expo = after.indexOf('/--/');
  if (expo >= 0) {
    rest = after.slice(expo + 3);
  } else if (/^https?$/i.test(protocol)) {
    // Drop the host.
    const slash = after.search(/[/?#]/);
    rest = slash >= 0 && after[slash] === '/' ? after.slice(slash) : '/';
  } else {
    // A custom scheme's "host" is the first path segment (filmfuse://sessions/x).
    rest = `/${after}`;
  }
  const path = rest.split(/[?#]/)[0] ?? '/';
  const trimmed = path.length > 1 ? path.replace(/\/+$/, '') : path;
  return trimmed || '/';
}
