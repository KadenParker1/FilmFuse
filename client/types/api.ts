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
 * alone; an empty string clears displayName / bio / profileImageUrl. Username is 3–30 chars of
 * [a-z0-9_] after the server trims and lowercases it.
 */
export interface UpdateProfileRequestDTO {
  username?: string | null;
  displayName?: string | null;
  bio?: string | null;
  profileImageUrl?: string | null;
}

/** com.pm.filmfuse.user.dto.UsernameAvailabilityDTO */
export interface UsernameAvailabilityDTO {
  username: string;
  valid: boolean;
  available: boolean;
}
