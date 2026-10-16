/**
 * Which in-app links may be remembered across sign-in: an invite (`/invite/<token>`) or a profile
 * (`/user/<id>`, e.g. from a friend-request email). Anything else is ignored, so a tampered storage
 * value can't send someone to an arbitrary route.
 */
const PENDING_LINK_RE = /^\/(invite|user)\/[^/?#\s]+$/;

export const isPendingLinkPath = (path: string | null | undefined): path is string =>
  typeof path === 'string' && PENDING_LINK_RE.test(path);

export const invitePath = (token: string) => `/invite/${encodeURIComponent(token)}`;
export const profilePath = (userId: string) => `/user/${encodeURIComponent(userId)}`;
