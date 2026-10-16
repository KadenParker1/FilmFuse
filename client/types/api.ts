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

// ---------------------------------------------------------------------------------------------
// Content comfort, groups & sessions (Phase 3) — com.pm.filmfuse.{user,reco,group}.dto
// ---------------------------------------------------------------------------------------------

/** Java enum `Certification` — US rating, ordered least → most mature. Display "PG-13" / "NC-17". */
export type Certification = 'G' | 'PG' | 'PG_13' | 'R' | 'NC_17';

/** Java enum `ContentDimension`. */
export type ContentDimension = 'VIOLENCE' | 'SEXUAL' | 'LANGUAGE' | 'DRUGS' | 'FRIGHTENING';

/** Java enum `ContentLevel` — ordered none → strong. */
export type ContentLevel = 'NONE' | 'MILD' | 'MODERATE' | 'STRONG';

/** Java enum `GroupRole`. */
export type GroupRole = 'OWNER' | 'MEMBER';

/** Java enum `MemberStatus`. */
export type MemberStatus = 'INVITED' | 'ACTIVE';

/** Java enum `SessionStatus` — OPEN sessions older than 24h report EXPIRED. */
export type SessionStatus = 'OPEN' | 'DECIDED' | 'EXPIRED';

/** Java enum `Vote`. */
export type Vote = 'UP' | 'DOWN' | 'VETO';

/** Java enum `Strategy` — how member fits are fused into one group score. */
export type Strategy = 'MEAN' | 'LEAST_MISERY' | 'MOST_PLEASURE' | 'CLOSENESS_WEIGHTED' | 'FAIRNESS';

/** Per-dimension ceilings; a missing dimension means STRONG (no limit). */
export type ContentLimits = Partial<Record<ContentDimension, ContentLevel>>;

/** com.pm.filmfuse.user.dto.ComfortSettingsDTO — GET/PUT /me/settings/comfort. `maxCertification` null = no limit. */
export interface ComfortSettingsDTO {
  maxCertification: Certification | null;
  contentLimits: Partial<Record<ContentDimension, ContentLevel>>;
  allowUnrated: boolean;
}

/**
 * com.pm.filmfuse.reco.dto.EffectiveFilterDTO — the most restrictive value across everyone present
 * (plus a group cap). Never says whose setting caused a limit. `label` e.g. "Up to PG-13 · mild violence".
 */
export interface EffectiveFilterDTO {
  maxCertification: Certification | null;
  contentLimits: Partial<Record<ContentDimension, ContentLevel>>;
  allowUnrated: boolean;
  label: string;
}

/** com.pm.filmfuse.group.dto.ComfortCapDTO — an owner-set ceiling for the whole group. */
export interface ComfortCapDTO {
  maxCertification: Certification | null;
  contentLimits: Partial<Record<ContentDimension, ContentLevel>>;
}

/** com.pm.filmfuse.group.dto.GroupMemberDTO — `joinedAt` is null while INVITED. */
export interface GroupMemberDTO {
  user: UserSummaryDTO;
  role: GroupRole;
  status: MemberStatus;
  joinedAt: string | null;
}

/** com.pm.filmfuse.group.dto.GroupSummaryDTO — `role` / `memberStatus` are the caller's; preview ≤ 5 active. */
export interface GroupSummaryDTO {
  id: string;
  name: string;
  emoji: string | null;
  color: string | null;
  role: GroupRole;
  memberStatus: MemberStatus;
  /** Active members only. */
  memberCount: number;
  memberPreview: UserSummaryDTO[];
  lastSessionAt: string | null;
}

/** com.pm.filmfuse.group.dto.GroupDetailDTO */
export interface GroupDetailDTO {
  id: string;
  name: string;
  emoji: string | null;
  color: string | null;
  ownerId: string;
  role: GroupRole;
  memberStatus: MemberStatus;
  members: GroupMemberDTO[];
  comfortCap: ComfortCapDTO;
  effectiveFilter: EffectiveFilterDTO;
  /** At most 5, newest first. */
  recentSessions: SessionSummaryDTO[];
}

/** com.pm.filmfuse.group.dto.CreateGroupDTO — name 1–40 (trimmed), emoji ≤ 8 chars, color `#RRGGBB`, 0–19 friends. */
export interface CreateGroupDTO {
  name: string;
  emoji?: string | null;
  color?: string | null;
  memberIds: string[];
}

/** com.pm.filmfuse.group.dto.UpdateGroupDTO — owner only; omitted / null = unchanged. */
export interface UpdateGroupDTO {
  name?: string | null;
  emoji?: string | null;
  color?: string | null;
  comfortCap?: ComfortCapDTO | null;
}

/** com.pm.filmfuse.group.dto.AddMembersDTO — 1–19 of the caller's own friends. */
export interface AddMembersDTO {
  userIds: string[];
}

/** com.pm.filmfuse.group.dto.SessionFiltersDTO — runtime 40–400 min, year 1900–2100. */
export interface SessionFiltersDTO {
  maxRuntime?: number | null;
  minYear?: number | null;
  genreIds?: number[] | null;
}

/** com.pm.filmfuse.group.dto.CreateSessionDTO — exactly one of `groupId` / `memberIds` (1–19 friends). */
export interface CreateSessionDTO {
  groupId?: string | null;
  memberIds?: string[] | null;
  filters?: SessionFiltersDTO | null;
  strategy?: Strategy | null;
}

/** com.pm.filmfuse.group.dto.SessionMemberDTO — `hasVector` false = no taste profile yet (popularity prior). */
export interface SessionMemberDTO {
  user: UserSummaryDTO;
  hasVector: boolean;
  voted: boolean;
}

/** com.pm.filmfuse.group.dto.VoteTallyDTO */
export interface VoteTallyDTO {
  up: number;
  down: number;
  vetoed: boolean;
}

/** com.pm.filmfuse.group.dto.SessionResultDTO — `memberScores` is userId → fit (1–10). */
export interface SessionResultDTO {
  rank: number;
  movie: MovieSummaryDTO;
  certification: Certification | null;
  groupScore: number;
  memberScores: Record<string, number>;
  explanation: string;
  votes: VoteTallyDTO;
  myVote: Vote | null;
}

/** com.pm.filmfuse.group.dto.SessionDTO — vetoed results are listed last with `votes.vetoed`. */
export interface SessionDTO {
  id: string;
  groupId: string | null;
  groupName: string | null;
  createdBy: UserSummaryDTO;
  status: SessionStatus;
  strategy: Strategy;
  filters: SessionFiltersDTO;
  effectiveFilter: EffectiveFilterDTO;
  members: SessionMemberDTO[];
  results: SessionResultDTO[];
  pickedMovie: MovieSummaryDTO | null;
  createdAt: string;
}

/** com.pm.filmfuse.group.dto.SessionSummaryDTO */
export interface SessionSummaryDTO {
  id: string;
  groupId: string | null;
  groupName: string | null;
  status: SessionStatus;
  pickedMovie: MovieSummaryDTO | null;
  memberCount: number;
  createdAt: string;
}

/** com.pm.filmfuse.group.dto.CastVoteDTO */
export interface CastVoteDTO {
  vote: Vote;
}

/** com.pm.filmfuse.group.dto.PickMovieDTO */
export interface PickMovieDTO {
  movieId: number;
}
