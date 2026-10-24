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
export type AccountStatus = 'ACTIVE' | 'SUSPENDED';

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
  /** UI gating only (shows the Admin entry); every /admin endpoint is enforced server-side. */
  isAdmin: boolean;
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

/** Java enum `SessionStatus` — OPEN sessions older than 24h report EXPIRED; so does one its creator ended (`endedEarly`). */
export type SessionStatus = 'OPEN' | 'DECIDED' | 'EXPIRED';

/** Java enum `SessionListStatus` — `GET /sessions?status=`: OPEN = "Live now", CLOSED = history. */
export type SessionListStatus = 'OPEN' | 'CLOSED';

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
  /** Up to 5 active members, leaving out anyone blocked either way with the viewer. */
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
  /** Every ACTIVE member. */
  memberCount: number;
  /** Every INVITED member. */
  invitedCount: number;
  /** Leaves out anyone blocked either way with the viewer, so it can be shorter than the counts. */
  members: GroupMemberDTO[];
  comfortCap: ComfortCapDTO;
  effectiveFilter: EffectiveFilterDTO;
  /** At most 5, newest first (page through all with `GET /sessions?groupId=`). */
  recentSessions: SessionSummaryDTO[];
  /** OPEN sessions of the group the caller isn't in yet (ACTIVE callers only); `POST /sessions/{id}/join`. */
  joinableSessions: SessionSummaryDTO[];
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
  /** null once the creator deleted their account, or when blocked either way with the viewer. */
  createdBy: UserSummaryDTO | null;
  status: SessionStatus;
  strategy: Strategy;
  filters: SessionFiltersDTO;
  effectiveFilter: EffectiveFilterDTO;
  /** Every member, including any left out of `members`. */
  memberCount: number;
  /** Members who have voted on any result, including any left out of `members`. */
  votedCount: number;
  /** Leaves out anyone blocked either way with the viewer (and their `memberScores` entries). */
  members: SessionMemberDTO[];
  results: SessionResultDTO[];
  pickedMovie: MovieSummaryDTO | null;
  /** OPEN only: results hidden because they no longer fit everyone's comfort settings (never says whose). */
  hiddenResultCount: number;
  /** When voting closes on its own (created + 24 h). */
  closesAt: string;
  /** EXPIRED because the creator ended it early. */
  endedEarly: boolean;
  /** The web link to this session (`APP_WEB_URL/sessions/{id}`). */
  shareUrl: string;
  createdAt: string;
}

/** com.pm.filmfuse.group.dto.SessionSummaryDTO — counts include everyone; preview ≤ 5, others before the viewer, blocks left out. */
export interface SessionSummaryDTO {
  id: string;
  groupId: string | null;
  groupName: string | null;
  status: SessionStatus;
  pickedMovie: MovieSummaryDTO | null;
  memberCount: number;
  votedCount: number;
  votedByMe: boolean;
  memberPreview: UserSummaryDTO[];
  closesAt: string;
  endedEarly: boolean;
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

// ---------------------------------------------------------------------------------------------
// Onboarding, ratings, taste & "For you" (Phase 1b) — com.pm.filmfuse.{user,rating,reco}.dto
// Every score is an integer 1–10.
// ---------------------------------------------------------------------------------------------

/** Java enum `RatingSource` — where a score came from. ONBOARDING_SWIPE only via POST /me/ratings/batch. */
export type RatingSource = 'ONBOARDING_SWIPE' | 'QUICK_RATE' | 'POST_WATCH';

/** Java enum `RatingVisibility` — calibration swipes are always PRIVATE. */
export type RatingVisibility = 'PRIVATE' | 'FRIENDS' | 'PUBLIC';

/** Java enum `VectorStatus` — NONE = no taste vector yet; REBUILDING = a newer signal is waiting to be applied. */
export type VectorStatus = 'NONE' | 'FRESH' | 'REBUILDING';

/** Java enum `VectorChangeReason` — why a taste-vector version was built. */
export type VectorChangeReason = 'ONBOARDING' | 'TASTE_EDIT' | 'RATING' | 'DISMISS' | 'PICK';

/** com.pm.filmfuse.user.dto.MoodDTO — GET /moods, fixed order; `tag` is a slug like "mind-bending". */
export interface MoodDTO {
  tag: string;
  label: string;
}

/** com.pm.filmfuse.user.dto.GenrePrefDTO — a stated genre preference (TMDB genre id, 1–10). */
export interface GenrePrefDTO {
  genreId: number;
  score: number;
}

/** com.pm.filmfuse.user.dto.MoodPrefDTO — a stated mood ("vibe") preference (1–10). */
export interface MoodPrefDTO {
  moodTag: string;
  score: number;
}

/** com.pm.filmfuse.user.dto.FavoriteDTO — PUT /me/preferences/favorites item; ranks start at 1. */
export interface FavoriteDTO {
  movieId: number;
  rank: number;
}

/** com.pm.filmfuse.user.dto.FavoriteMovieDTO */
export interface FavoriteMovieDTO {
  rank: number;
  movie: MovieSummaryDTO;
}

/** com.pm.filmfuse.user.dto.OnboardingStateDTO — GET /me/onboarding; everything saved so far (resumable). */
export interface OnboardingStateDTO {
  /** 0–8; only ever increases server-side. */
  step: number;
  completed: boolean;
  genrePrefs: GenrePrefDTO[];
  moodPrefs: MoodPrefDTO[];
  favorites: FavoriteMovieDTO[];
  calibrationRatedCount: number;
  comfort: ComfortSettingsDTO;
}

/** com.pm.filmfuse.user.dto.UpdateOnboardingStepDTO — step 0–8. */
export interface UpdateOnboardingStepDTO {
  step: number;
}

/** com.pm.filmfuse.rating.dto.CalibrationRatingDTO — exactly one of `score` / `unseen: true`. */
export interface CalibrationRatingDTO {
  movieId: number;
  score: number | null;
  unseen: boolean;
}

/** com.pm.filmfuse.rating.dto.RatingDTO — a "review" is a rating with a note and visibility ≠ PRIVATE. */
export interface RatingDTO {
  id: string;
  movie: MovieSummaryDTO;
  score: number;
  body: string | null;
  containsSpoilers: boolean;
  visibility: RatingVisibility;
  source: RatingSource;
  /** ISO date (yyyy-MM-dd). */
  watchedOn: string | null;
  createdAt: string;
  updatedAt: string;
}

/** com.pm.filmfuse.rating.dto.UpsertRatingDTO — PUT /ratings/{movieId}; body ≤ 1000 chars. */
export interface UpsertRatingDTO {
  score: number;
  body?: string | null;
  containsSpoilers?: boolean;
  /** Defaults to FRIENDS. */
  visibility?: RatingVisibility;
  watchedOn?: string | null;
  /** Defaults to QUICK_RATE; ONBOARDING_SWIPE is rejected here. */
  source?: RatingSource;
}

/** com.pm.filmfuse.reco.dto.TasteDimensionDTO — `key` is "genre:28" or "mood:cozy"; `learned` comes from the vector. */
export interface TasteDimensionDTO {
  key: string;
  label: string;
  stated: number | null;
  learned: number | null;
}

/** com.pm.filmfuse.reco.dto.TasteProfileDTO — GET /me/taste-profile. */
export interface TasteProfileDTO {
  status: VectorStatus;
  updatedAt: string | null;
  modelVersion: string | null;
  persona: string | null;
  /** ≤ 5, by learned (falling back to stated), highest first. */
  topGenres: TasteDimensionDTO[];
  /** Every genre, then every mood, in the fixed vector order. */
  dimensions: TasteDimensionDTO[];
  ratingsCount: number;
}

/** com.pm.filmfuse.reco.dto.TasteHistoryEntryDTO — GET /me/taste/history, newest first. */
export interface TasteHistoryEntryDTO {
  version: number;
  reason: VectorChangeReason;
  modelVersion: string;
  createdAt: string;
  /** ≤ 3. */
  topGenres: TasteDimensionDTO[];
}

/** com.pm.filmfuse.reco.dto.RecommendationDTO — one "For you" pick. */
export interface RecommendationDTO {
  movie: MovieSummaryDTO;
  certification: Certification | null;
  score: number;
  explanation: string;
}

// ---------------------------------------------------------------------------------------------
// Ratings feed (Phase 2b) — com.pm.filmfuse.rating.dto
// ---------------------------------------------------------------------------------------------

/** Java enum `FeedScope` — FRIENDS = your friends' and your own visible ratings; EVERYONE = public ratings. */
export type FeedScope = 'FRIENDS' | 'EVERYONE';

/**
 * com.pm.filmfuse.rating.dto.FeedItemDTO — one visible rating in a feed, a movie's ratings or a
 * person's ratings. `id` is the rating id; `mine` = written by the caller.
 */
export interface FeedItemDTO {
  id: string;
  author: UserSummaryDTO;
  movie: MovieSummaryDTO;
  certification: Certification | null;
  score: number;
  body: string | null;
  containsSpoilers: boolean;
  visibility: RatingVisibility;
  likeCount: number;
  likedByMe: boolean;
  /** Comments the caller can see (blocked people's are left out). */
  commentCount: number;
  mine: boolean;
  createdAt: string;
  updatedAt: string;
}

// ---------------------------------------------------------------------------------------------
// Polish (Phase 5) — post-watch prompts, comments, reports, notification preferences
// ---------------------------------------------------------------------------------------------

/**
 * com.pm.filmfuse.rating.dto.PendingRatingDTO — a movie picked in one of the caller's sessions in
 * the last 14 days that they haven't rated or dismissed. `groupName` is null for ad-hoc sessions.
 */
export interface PendingRatingDTO {
  movie: MovieSummaryDTO;
  sessionId: string;
  groupName: string | null;
  pickedAt: string;
}

/** com.pm.filmfuse.rating.dto.CommentDTO — `canDelete` = the comment's author or the rating's author. */
export interface CommentDTO {
  id: string;
  ratingId: string;
  author: UserSummaryDTO;
  body: string;
  createdAt: string;
  mine: boolean;
  canDelete: boolean;
}

/** com.pm.filmfuse.rating.dto.CreateCommentDTO — body 1–500 chars after trimming. */
export interface CreateCommentDTO {
  body: string;
}

/** Java enum `ReportTargetType` (moderation module). */
export type ReportTargetType = 'RATING' | 'COMMENT' | 'USER';

/** Java enum `ReportReason` (moderation module). */
export type ReportReason = 'SPAM' | 'HARASSMENT' | 'HATE' | 'SEXUAL_CONTENT' | 'SPOILERS' | 'OTHER';

/** com.pm.filmfuse.moderation.dto.CreateReportDTO — POST /reports; `details` ≤ 500 chars. */
export interface CreateReportDTO {
  targetType: ReportTargetType;
  targetId: string;
  reason: ReportReason;
  details?: string | null;
}

/** com.pm.filmfuse.user.dto.NotificationSettingsDTO — email preferences; all on by default except the digest. */
export interface NotificationSettingsDTO {
  friendRequests: boolean;
  groupInvites: boolean;
  sessionStarted: boolean;
  comments: boolean;
  weeklyDigest: boolean;
}

// ------------------------------------------------------------------------------------ admin portal

/** Java enum `AdminSettingType` (admin module). */
export type AdminSettingType = 'INTEGER' | 'BOOLEAN';

/** Java enum `AdminAction` (admin module) — what an audit-log row records. */
export type AdminAction =
  | 'SETTING_UPDATE'
  | 'SETTING_RESET'
  | 'JOB_RUN'
  | 'JOB_PAUSE'
  | 'JOB_RESUME'
  | 'REPORT_RESOLVE'
  | 'REPORT_DISMISS';

/** Java enum `ReportStatus` (admin module). */
export type ReportStatus = 'OPEN' | 'RESOLVED' | 'DISMISSED';

/** Java enum `ReportResolution` (admin module). */
export type ReportResolution = 'RESOLVED' | 'DISMISSED';

/** com.pm.filmfuse.admin.dto.AdminUserRefDTO — a user as admin lists name them (no email). */
export interface AdminUserRefDTO {
  id: string;
  username: string | null;
  displayName: string | null;
}

/**
 * com.pm.filmfuse.admin.dto.AdminSettingDTO — `value` is the effective value (override or default):
 * a number for INTEGER, a boolean for BOOLEAN; `min`/`max` are inclusive bounds (INTEGER only).
 */
export interface AdminSettingDTO {
  key: string;
  group: string;
  label: string;
  description: string;
  unit: string | null;
  type: AdminSettingType;
  value: number | boolean;
  defaultValue: number | boolean;
  overridden: boolean;
  min: number | null;
  max: number | null;
  updatedAt: string | null;
  updatedBy: AdminUserRefDTO | null;
}

/** com.pm.filmfuse.admin.dto.UpdateAdminSettingDTO — PUT /admin/settings/{key}. */
export interface UpdateAdminSettingDTO {
  value: number | boolean;
}

/** com.pm.filmfuse.admin.dto.AdminAuditEntryDTO — `admin` is null once that account is deleted. */
export interface AdminAuditEntryDTO {
  id: string;
  admin: AdminUserRefDTO | null;
  action: AdminAction;
  target: string | null;
  oldValue: unknown;
  newValue: unknown;
  createdAt: string;
}

/** com.pm.filmfuse.admin.dto.ReportTargetPreviewDTO — `exists` is false once the target was deleted. */
export interface ReportTargetPreviewDTO {
  exists: boolean;
  author: AdminUserRefDTO | null;
  text: string | null;
  movieId: number | null;
  movieTitle: string | null;
  score: number | null;
  authorStatus: AccountStatus | null;
}

/** com.pm.filmfuse.admin.dto.AdminReportDTO — one report in the moderation queue. */
export interface AdminReportDTO {
  id: string;
  targetType: ReportTargetType;
  targetId: string;
  reason: ReportReason;
  details: string | null;
  status: ReportStatus;
  createdAt: string;
  reporter: AdminUserRefDTO;
  target: ReportTargetPreviewDTO;
  openReportsOnTarget: number;
  resolvedAt: string | null;
  resolvedBy: AdminUserRefDTO | null;
  resolutionNote: string | null;
}

/** com.pm.filmfuse.admin.dto.ResolveReportDTO — POST /admin/reports/{id}/resolve; note ≤ 500 chars. */
export interface ResolveReportDTO {
  resolution: ReportResolution;
  note?: string | null;
}

/** com.pm.filmfuse.admin.dto.VoteTiersDTO — exclusive TMDB vote-count bins. */
export interface VoteTiersDTO {
  votes1000Plus: number;
  votes100To999: number;
  votes20To99: number;
  votesUnder20: number;
  votesUnknown: number;
}

/** com.pm.filmfuse.admin.dto.CatalogMetricsDTO — GET /admin/metrics/catalog. */
export interface CatalogMetricsDTO {
  totalMovies: number;
  withCertification: number;
  voteTiers: VoteTiersDTO;
  rejected: number;
  databaseBytes: number;
  maxDatabaseMb: number;
  maxDatabaseMbCeiling: number;
  planLimitMb: number;
  generatedAt: string;
}

/** com.pm.filmfuse.admin.dto.DailyCountDTO — `date` is a UTC day, yyyy-MM-dd. */
export interface DailyCountDTO {
  date: string;
  count: number;
}

/** com.pm.filmfuse.admin.dto.UsageMetricsDTO — GET /admin/metrics/usage; ratingsPerDay = 30 UTC days, oldest first. */
export interface UsageMetricsDTO {
  usersTotal: number;
  usersNew7d: number;
  usersOnboarded: number;
  ratingsPerDay: DailyCountDTO[];
  sessionsStarted7d: number;
  sessionsStarted30d: number;
  sessionsPicked7d: number;
  sessionsPicked30d: number;
  activeGroups30d: number;
  generatedAt: string;
}

/** com.pm.filmfuse.admin.dto.MlHealthDTO — python-ml-service GET /health. */
export interface MlHealthDTO {
  configured: boolean;
  reachable: boolean;
  status: string | null;
  modelVersion: string | null;
  latencyMs: number | null;
}

/** com.pm.filmfuse.admin.dto.HealthMetricsDTO — GET /admin/metrics/health. */
export interface HealthMetricsDTO {
  ml: MlHealthDTO;
  vectorsDirty: number;
  vectorsDirtyOldest: string | null;
  vectorsNeverBuilt: number;
  openReports: number;
  generatedAt: string;
}

/** Java enum `JobOutcome` (common.job) — how a recorded job run ended. */
export type JobOutcome = 'RUNNING' | 'COMPLETED' | 'ABORTED' | 'INTERRUPTED' | 'FAILED' | 'SKIPPED';

/** com.pm.filmfuse.admin.dto.JobRunDTO — one app.job_runs row; finishedAt null while running. */
export interface JobRunDTO {
  id: number;
  startedAt: string;
  finishedAt: string | null;
  outcome: JobOutcome;
  summary: Record<string, unknown>;
  triggeredBy: string | null;
}

/** com.pm.filmfuse.admin.dto.AdminJobDTO — GET /admin/jobs. `controllable` = Run now / Pause / Resume apply. */
export interface AdminJobDTO {
  name: string;
  label: string;
  scheduled: boolean;
  paused: boolean;
  controllable: boolean;
  schedule: string | null;
  nextRunAt: string | null;
  running: boolean;
  lastRun: JobRunDTO | null;
  recentRuns: JobRunDTO[];
}

/** com.pm.filmfuse.admin.dto.JobRunStartedDTO — 202 from POST /admin/jobs/{name}/run. */
export interface JobRunStartedDTO {
  jobName: string;
  runId: number | null;
  triggeredBy: string;
}
