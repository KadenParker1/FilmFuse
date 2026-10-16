/**
 * Every TanStack Query key in the app, in one place. A prefix is the parent node and specific keys
 * spread it, so invalidation prefixes can't drift from the keys they invalidate.
 */
const movies = ['movies'] as const;

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
} as const;
