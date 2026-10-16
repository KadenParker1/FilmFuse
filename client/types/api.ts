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

// ---------------------------------------------------------------------------------------------
// Social graph (Phase 2) — com.pm.filmfuse.social.dto
// ---------------------------------------------------------------------------------------------

/** Java enum `FriendshipState` (social module) — relative to the caller; BLOCKED = the caller blocked them. */
export type FriendshipState = 'NONE' | 'FRIENDS' | 'REQUEST_SENT' | 'REQUEST_RECEIVED' | 'BLOCKED';

/** Java enum `RequestDirection` (social module). */
export type RequestDirection = 'INCOMING' | 'OUTGOING';

/** com.pm.filmfuse.social.dto.UserSummaryDTO */
export interface UserSummaryDTO {
  id: string;
  /** Null until they pick one (e.g. joined through an invite). */
  username: string | null;
  displayName: string | null;
  profileImageUrl: string | null;
  friendship: FriendshipState;
}

/** com.pm.filmfuse.social.dto.PublicProfileDTO — `tasteOverlap` stays null until taste vectors exist (Phase 1b). */
export interface PublicProfileDTO {
  id: string;
  /** Null until they pick one (e.g. joined through an invite). */
  username: string | null;
  displayName: string | null;
  profileImageUrl: string | null;
  bio: string | null;
  friendship: FriendshipState;
  /** True only when you are FRIENDS and you flagged them as a close friend (directional). */
  closeFriend: boolean;
  mutualFriendCount: number;
  friendCount: number;
  tasteOverlap: number | null;
}

/** com.pm.filmfuse.social.dto.FriendDTO — `closeness` is 0–1; `friendsSince` is ISO-8601. */
export interface FriendDTO {
  id: string;
  /** Null until they pick one (e.g. joined through an invite). */
  username: string | null;
  displayName: string | null;
  profileImageUrl: string | null;
  closeFriend: boolean;
  closeness: number;
  friendsSince: string;
}

/** com.pm.filmfuse.social.dto.FriendRequestDTO */
export interface FriendRequestDTO {
  user: UserSummaryDTO;
  direction: RequestDirection;
  createdAt: string;
}

/** com.pm.filmfuse.social.dto.FriendshipStatusDTO */
export interface FriendshipStatusDTO {
  userId: string;
  friendship: FriendshipState;
}

/** com.pm.filmfuse.social.dto.FriendSuggestionDTO — `mutualFriends` holds at most 3 people. */
export interface FriendSuggestionDTO {
  user: UserSummaryDTO;
  mutualFriendCount: number;
  mutualFriends: UserSummaryDTO[];
}

/** com.pm.filmfuse.social.dto.SendFriendRequestDTO */
export interface SendFriendRequestDTO {
  targetUserId: string;
}

/** com.pm.filmfuse.social.dto.UpdateFriendDTO */
export interface UpdateFriendDTO {
  closeFriend: boolean;
}

/** com.pm.filmfuse.social.dto.InviteDTO — `url` is the web link to share (`<web-url>/invite/<token>`). */
export interface InviteDTO {
  token: string;
  url: string;
  expiresAt: string;
}

/** com.pm.filmfuse.social.dto.InviteRedeemResultDTO — `inviter.friendship` is FRIENDS after success. */
export interface InviteRedeemResultDTO {
  inviter: UserSummaryDTO;
}
