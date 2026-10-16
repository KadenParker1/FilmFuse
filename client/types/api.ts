/**
 * Hand-written mirrors of core-api DTOs. Keep in sync with
 * core-api/src/main/java/com/pm/filmfuse/** /dto — CI checks for drift.
 * Java `Instant` arrives as an ISO-8601 string; `UUID` as a string.
 */

/** com.pm.filmfuse.common.exception.ErrorResponseDTO */
export interface ErrorResponseDTO {
  message: string;
  /** field name → message; present only for validation failures. */
  fieldErrors?: Record<string, string>;
}

/** com.pm.filmfuse.user.model.AccountStatus */
export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'PENDING_VERIFICATION';

/** com.pm.filmfuse.user.dto.ProfileResponseDTO */
export interface ProfileResponseDTO {
  id: string;
  username: string | null;
  displayName: string | null;
  profileImageUrl: string | null;
  bio: string | null;
  status: AccountStatus;
  onboardingStep: number;
  onboardingCompleted: boolean;
  onboardingCompletedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * com.pm.filmfuse.user.dto.UpdateProfileRequestDTO — a PATCH: omit (or send null) to leave a field
 * alone; an empty string clears displayName / bio. Username is 3–30 chars of [a-z0-9_] after the
 * server trims and lowercases it. The profile photo changes only via the /me/profile-photo endpoints.
 */
export interface UpdateProfileRequestDTO {
  username?: string | null;
  displayName?: string | null;
  bio?: string | null;
}

/** com.pm.filmfuse.user.dto.UsernameAvailabilityDTO */
export interface UsernameAvailabilityDTO {
  username: string;
  valid: boolean;
  available: boolean;
}

/** com.pm.filmfuse.user.dto.ProfilePhotoUploadDTO — `uploadUrl` is an absolute Supabase Storage signed-upload URL. */
export interface ProfilePhotoUploadDTO {
  uploadUrl: string;
  /** `<userId>/<uuid>.jpg` in the `profile-photos` bucket. */
  path: string;
  expiresInSeconds: number;
}

/** com.pm.filmfuse.user.dto.ConfirmProfilePhotoRequestDTO */
export interface ConfirmProfilePhotoRequestDTO {
  path: string;
}

/** com.pm.filmfuse.common.dto.PageDTO — `nextCursor` is opaque; null means no more pages. */
export interface PageDTO<T> {
  items: T[];
  nextCursor: string | null;
}

/** com.pm.filmfuse.movie.dto.GenreDTO (TMDB genre id). */
export interface GenreDTO {
  id: number;
  name: string;
}

/** com.pm.filmfuse.movie.dto.MovieSummaryDTO — `id` is the TMDB id; image URLs are absolute or null. */
export interface MovieSummaryDTO {
  id: number;
  title: string;
  releaseYear: number | null;
  posterUrl: string | null;
  genreIds: number[];
  voteAverage: number | null;
}

/** com.pm.filmfuse.movie.dto.MovieDetailDTO */
export interface MovieDetailDTO {
  id: number;
  title: string;
  releaseYear: number | null;
  runtime: number | null;
  overview: string | null;
  posterUrl: string | null;
  backdropUrl: string | null;
  genres: GenreDTO[];
  voteAverage: number | null;
  popularity: number | null;
}
