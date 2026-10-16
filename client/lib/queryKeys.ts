/**
 * Every TanStack Query key in the app, in one place. A prefix is the parent node and specific keys
 * spread it, so invalidation prefixes can't drift from the keys they invalidate.
 */
const movies = ['movies'] as const;
const social = ['social'] as const;
const userSearchAll = [...social, 'search'] as const;
const friendRequestsAll = [...social, 'requests'] as const;
const friendSuggestionsAll = [...social, 'suggestions'] as const;
const groups = ['groups'] as const;
const sessions = ['sessions'] as const;
const sessionListAll = [...sessions, 'list'] as const;

export const queryKeys = {
  /** The signed-in user's own profile (GET /me). */
  me: ['me'] as const,
  /** GET /usernames/{u}/available */
  usernameAvailability: (username: string) => ['usernames', username, 'available'] as const,
  /** GET /genres */
  genres: ['genres'] as const,
  movies,
  /** GET /movies/search?q= (infinite) */
  movieSearch: (q: string) => [...movies, 'search', q] as const,
  /** GET /movies/popular?genre= (infinite); null = all genres */
  popularMovies: (genreId: number | null) => [...movies, 'popular', genreId] as const,
  /** GET /movies/{id} */
  movie: (id: number) => [...movies, 'detail', id] as const,
  /** Everything social-graph related (prefix). */
  social,
  /** Every GET /users/search page set (prefix). */
  userSearchAll,
  /** GET /users/search?q= (infinite) */
  userSearch: (q: string) => [...userSearchAll, q] as const,
  /** GET /users/{id} */
  publicProfile: (id: string) => [...social, 'profile', id] as const,
  /** GET /friends (infinite) */
  friends: [...social, 'friends'] as const,
  /** Both GET /friends/requests lists (prefix). */
  friendRequestsAll,
  /** GET /friends/requests?direction= */
  friendRequests: (direction: 'INCOMING' | 'OUTGOING') => [...friendRequestsAll, direction] as const,
  /** Every GET /friends/suggestions list (prefix). */
  friendSuggestionsAll,
  /** GET /friends/suggestions?limit=; null = the server default */
  friendSuggestions: (limit: number | null) => [...friendSuggestionsAll, limit] as const,
  /** GET /blocks */
  blocks: [...social, 'blocks'] as const,
  /** GET /me/settings/comfort */
  comfortSettings: ['settings', 'comfort'] as const,
  /** Everything group related (prefix). */
  groups,
  /** GET /groups */
  groupList: [...groups, 'list'] as const,
  /** GET /groups/{id} */
  group: (id: string) => [...groups, 'detail', id] as const,
  /** Everything session related (prefix). */
  sessions,
  /** GET /sessions/{id} */
  session: (id: string) => [...sessions, 'detail', id] as const,
  /** Every GET /sessions history list (prefix). */
  sessionListAll,
  /** GET /sessions?groupId= (infinite); null = all of the caller's sessions */
  sessionList: (groupId: string | null) => [...sessionListAll, groupId] as const,
} as const;
