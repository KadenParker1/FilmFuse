import type { AdminAction, FeedScope, ReportStatus, SessionListStatus } from '../types/api';

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
const ratings = ['ratings'] as const;
const taste = ['taste'] as const;
const calibration = ['calibration'] as const;
const feed = ['feed'] as const;
const movieRatingsAll = [...feed, 'movie'] as const;
const ratingDetailAll = ['ratingDetail'] as const;
const commentsAll = ['comments'] as const;
const admin = ['admin'] as const;
const adminReportsAll = [...admin, 'reports'] as const;
const adminAuditAll = [...admin, 'audit'] as const;

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
  /** GET /sessions?groupId=&status= (infinite); null = all of the caller's sessions / every status */
  sessionList: (groupId: string | null, status: SessionListStatus | null = null) =>
    [...sessionListAll, groupId, status] as const,
  /** GET /sessions?status=OPEN&limit=50 — "Live now" and the Groups tab badge (one page). */
  liveSessions: [...sessionListAll, 'live'] as const,
  /** GET /moods */
  moods: ['moods'] as const,
  /** GET /me/onboarding */
  onboarding: ['onboarding'] as const,
  /** Every calibration batch (prefix). */
  calibration,
  /** GET /me/calibration/next — one deck's `batch`-th batch (`deck` keeps separate decks apart). */
  calibrationBatch: (deck: string, batch: number) => [...calibration, deck, batch] as const,
  /** Everything rating related (prefix). */
  ratings,
  /** GET /me/ratings (infinite) */
  myRatings: [...ratings, 'list'] as const,
  /** GET /me/ratings/{movieId}; null data = not rated */
  myRating: (movieId: number) => [...ratings, 'movie', movieId] as const,
  /** Everything taste related (prefix). */
  taste,
  /** GET /me/taste-profile */
  tasteProfile: [...taste, 'profile'] as const,
  /** GET /me/taste/history?limit= */
  tasteHistory: (limit: number | null) => [...taste, 'history', limit] as const,
  /** GET /me/recommendations (infinite) */
  recommendations: ['recommendations'] as const,
  /** Every list of FeedItemDTOs — feeds, a movie's ratings, a person's ratings (prefix). */
  feed,
  /** GET /feed?scope= (infinite) */
  feedList: (scope: FeedScope) => [...feed, 'list', scope] as const,
  /** Every GET /movies/{id}/ratings list (prefix). */
  movieRatingsAll,
  /** Both scopes of one movie's ratings (prefix). */
  movieRatingsFor: (movieId: number) => [...movieRatingsAll, movieId] as const,
  /** GET /movies/{id}/ratings?scope= (infinite) */
  movieRatings: (movieId: number, scope: FeedScope) => [...movieRatingsAll, movieId, scope] as const,
  /** GET /users/{id}/ratings (infinite) */
  userRatings: (userId: string) => [...feed, 'user', userId] as const,
  /** Every GET /ratings/{ratingId} (prefix). Not under `feed`: these hold one item, not pages. */
  ratingDetailAll,
  /** GET /ratings/{ratingId} — one visible rating (the rating detail screen). */
  ratingDetail: (ratingId: string) => [...ratingDetailAll, ratingId] as const,
  /** Every comment list (prefix). */
  commentsAll,
  /** GET /ratings/{ratingId}/comments (infinite, oldest first) */
  comments: (ratingId: string) => [...commentsAll, ratingId] as const,
  /** GET /me/pending-ratings — "Rate what you watched". */
  pendingRatings: ['pendingRatings'] as const,
  /** GET /me/settings/notifications */
  notificationSettings: ['settings', 'notifications'] as const,
  /** Everything in the admin portal (prefix). */
  admin,
  /** GET /admin/metrics/catalog */
  adminCatalogMetrics: [...admin, 'metrics', 'catalog'] as const,
  /** GET /admin/metrics/usage */
  adminUsageMetrics: [...admin, 'metrics', 'usage'] as const,
  /** GET /admin/metrics/health */
  adminHealthMetrics: [...admin, 'metrics', 'health'] as const,
  /** GET /admin/jobs */
  adminJobs: [...admin, 'jobs'] as const,
  /** GET /admin/settings */
  adminSettings: [...admin, 'settings'] as const,
  /** Every reports queue (prefix). */
  adminReportsAll,
  /** GET /admin/reports?status= (infinite) */
  adminReports: (status: ReportStatus) => [...adminReportsAll, status] as const,
  /** Every audit-log list (prefix). */
  adminAuditAll,
  /** GET /admin/audit-log?action= (infinite); null = every action */
  adminAudit: (action: AdminAction | null) => [...adminAuditAll, action] as const,
} as const;
