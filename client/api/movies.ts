import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type { GenreDTO, MovieDetailDTO, MovieSummaryDTO, PageDTO } from '../types/api';

/** Search queries are 1–100 chars server-side; anything else is never sent. */
export const SEARCH_MAX_LENGTH = 100;

export const fetchGenres = () => apiClient.get<GenreDTO[]>('/genres');

export const fetchMovieSearch = (q: string, cursor?: string | null) =>
  apiClient.get<PageDTO<MovieSummaryDTO>>(withQuery('/movies/search', { q, cursor }));

export const fetchPopularMovies = (genreId?: number | null, cursor?: string | null) =>
  apiClient.get<PageDTO<MovieSummaryDTO>>(withQuery('/movies/popular', { genre: genreId, cursor }));

export const fetchMovie = (id: number) => apiClient.get<MovieDetailDTO>(`/movies/${id}`);

/** The 19 TMDB movie genres. They practically never change, so they're cached for the session. */
export function useGenres() {
  return useQuery({ queryKey: queryKeys.genres, queryFn: fetchGenres, staleTime: Infinity });
}

/**
 * Paged search. Pass an already-debounced, trimmed query; it's disabled while empty (or too long).
 * `nextCursor: null` ends paging.
 */
export function useMovieSearch(q: string) {
  const query = q.trim();
  return useInfiniteQuery({
    queryKey: queryKeys.movieSearch(query),
    queryFn: ({ pageParam }) => fetchMovieSearch(query, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: query.length > 0 && query.length <= SEARCH_MAX_LENGTH,
  });
}

/** Popular movies, optionally filtered to one TMDB genre id. */
export function usePopularMovies(genreId?: number | null) {
  const genre = genreId ?? null;
  return useInfiniteQuery({
    queryKey: queryKeys.popularMovies(genre),
    queryFn: ({ pageParam }) => fetchPopularMovies(genre, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

export function useMovie(id: number) {
  return useQuery({
    queryKey: queryKeys.movie(id),
    queryFn: () => fetchMovie(id),
    enabled: Number.isInteger(id) && id > 0,
  });
}

/** Flattens infinite-query pages into one list. */
export function flattenPages<T>(data: { pages: PageDTO<T>[] } | undefined): T[] {
  return data?.pages.flatMap((p) => p.items) ?? [];
}

/** A detail record trimmed to the summary shape (what the quick-rate sheet and lists take). */
export function toMovieSummary(movie: MovieDetailDTO): MovieSummaryDTO {
  return {
    id: movie.id,
    title: movie.title,
    releaseYear: movie.releaseYear,
    posterUrl: movie.posterUrl,
    genreIds: movie.genres.map((g) => g.id),
    voteAverage: movie.voteAverage,
  };
}
