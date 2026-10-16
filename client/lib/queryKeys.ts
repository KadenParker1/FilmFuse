/**
 * Every TanStack Query key in the app, in one place. A prefix is the parent node and specific keys
 * spread it, so invalidation prefixes can't drift from the keys they invalidate.
 */
export const queryKeys = {
  /** The signed-in user's own profile (GET /me). */
  me: ['me'] as const,
} as const;
