import type {
  CommentDTO,
  NotificationSettingsDTO,
  PendingRatingDTO,
  ComfortSettingsDTO,
  EffectiveFilterDTO,
  FeedItemDTO,
  FriendDTO,
  GroupDetailDTO,
  GroupMemberDTO,
  GroupSummaryDTO,
  MovieSummaryDTO,
  OnboardingStateDTO,
  RatingDTO,
  RecommendationDTO,
  SessionDTO,
  TasteDimensionDTO,
  TasteHistoryEntryDTO,
  TasteProfileDTO,
  SessionMemberDTO,
  SessionResultDTO,
  SessionSummaryDTO,
  FriendRequestDTO,
  FriendSuggestionDTO,
  ProfileResponseDTO,
  PublicProfileDTO,
  RequestDirection,
  UserSummaryDTO,
} from '../types/api';

export const makeProfile = (overrides: Partial<ProfileResponseDTO> = {}): ProfileResponseDTO => ({
  id: 'u1',
  username: 'ada',
  displayName: 'Ada Lovelace',
  profileImageUrl: null,
  bio: 'Loves heist films.',
  status: 'ACTIVE',
  onboardingStep: 0,
  onboardingCompleted: true,
  onboardingCompletedAt: '2026-01-01T00:00:00Z',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  ...overrides,
});

export const makeUser = (overrides: Partial<UserSummaryDTO> = {}): UserSummaryDTO => ({
  id: 'u2',
  username: 'grace',
  displayName: 'Grace Hopper',
  profileImageUrl: null,
  friendship: 'NONE',
  ...overrides,
});

export const makePublicProfile = (overrides: Partial<PublicProfileDTO> = {}): PublicProfileDTO => ({
  id: 'u2',
  username: 'grace',
  displayName: 'Grace Hopper',
  profileImageUrl: null,
  bio: 'Debugging since 1947.',
  friendship: 'NONE',
  closeFriend: false,
  mutualFriendCount: 2,
  friendCount: 12,
  tasteOverlap: null,
  ...overrides,
});

export const makeFriend = (overrides: Partial<FriendDTO> = {}): FriendDTO => ({
  id: 'u2',
  username: 'grace',
  displayName: 'Grace Hopper',
  profileImageUrl: null,
  closeFriend: false,
  closeness: 0.5,
  friendsSince: '2026-02-01T00:00:00Z',
  ...overrides,
});

export const makeRequest = (
  user: Partial<UserSummaryDTO> = {},
  direction: RequestDirection = 'INCOMING',
): FriendRequestDTO => ({
  user: makeUser({ friendship: direction === 'INCOMING' ? 'REQUEST_RECEIVED' : 'REQUEST_SENT', ...user }),
  direction,
  createdAt: '2026-03-01T00:00:00Z',
});

export const makeSuggestion = (user: Partial<UserSummaryDTO> = {}, mutualFriendCount = 1): FriendSuggestionDTO => ({
  user: makeUser(user),
  mutualFriendCount,
  mutualFriends: [],
});

// ---- Phase 3: comfort, groups, sessions ------------------------------------------------------

export const makeMovie = (overrides: Partial<MovieSummaryDTO> = {}): MovieSummaryDTO => ({
  id: 100,
  title: 'Heat',
  releaseYear: 1995,
  posterUrl: null,
  genreIds: [80],
  voteAverage: 8.3,
  ...overrides,
});

export const makeComfort = (overrides: Partial<ComfortSettingsDTO> = {}): ComfortSettingsDTO => ({
  maxCertification: null,
  contentLimits: {},
  allowUnrated: false,
  ...overrides,
});

export const makeEffectiveFilter = (overrides: Partial<EffectiveFilterDTO> = {}): EffectiveFilterDTO => ({
  maxCertification: null,
  contentLimits: {},
  allowUnrated: false,
  label: 'No limits',
  ...overrides,
});

export const makeSessionSummary = (overrides: Partial<SessionSummaryDTO> = {}): SessionSummaryDTO => ({
  id: 's1',
  groupId: 'g1',
  groupName: 'Film club',
  status: 'DECIDED',
  pickedMovie: makeMovie(),
  memberCount: 3,
  createdAt: '2026-10-10T20:00:00Z',
  ...overrides,
});

export const makeGroupSummary = (overrides: Partial<GroupSummaryDTO> = {}): GroupSummaryDTO => ({
  id: 'g1',
  name: 'Film club',
  emoji: '🎬',
  color: '#C7354A',
  role: 'OWNER',
  memberStatus: 'ACTIVE',
  memberCount: 3,
  memberPreview: [makeUser({ id: 'u1', displayName: 'Ada Lovelace', username: 'ada' }), makeUser()],
  lastSessionAt: null,
  ...overrides,
});

export const makeGroupMember = (
  user: Partial<UserSummaryDTO> = {},
  overrides: Partial<GroupMemberDTO> = {},
): GroupMemberDTO => ({
  user: makeUser({ friendship: 'FRIENDS', ...user }),
  role: 'MEMBER',
  status: 'ACTIVE',
  joinedAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

export const makeGroup = (overrides: Partial<GroupDetailDTO> = {}): GroupDetailDTO => ({
  id: 'g1',
  name: 'Film club',
  emoji: '🎬',
  color: '#C7354A',
  ownerId: 'u1',
  role: 'OWNER',
  memberStatus: 'ACTIVE',
  members: [
    makeGroupMember({ id: 'u1', displayName: 'Ada Lovelace', username: 'ada', friendship: 'NONE' }, { role: 'OWNER' }),
    makeGroupMember(),
    makeGroupMember({ id: 'u3', displayName: 'Alan Turing', username: 'alan' }, { status: 'INVITED', joinedAt: null }),
  ],
  comfortCap: { maxCertification: null, contentLimits: {} },
  effectiveFilter: makeEffectiveFilter(),
  recentSessions: [],
  ...overrides,
});

export const makeSessionMember = (
  user: Partial<UserSummaryDTO> = {},
  overrides: Partial<SessionMemberDTO> = {},
): SessionMemberDTO => ({
  user: makeUser(user),
  hasVector: true,
  voted: false,
  ...overrides,
});

export const makeResult = (overrides: Partial<SessionResultDTO> = {}): SessionResultDTO => ({
  rank: 1,
  movie: makeMovie(),
  certification: 'R',
  groupScore: 7.5,
  memberScores: { u1: 9, u2: 6 },
  explanation: 'Crime thriller you both rate highly',
  votes: { up: 0, down: 0, vetoed: false },
  myVote: null,
  ...overrides,
});

export const makeSession = (overrides: Partial<SessionDTO> = {}): SessionDTO => ({
  id: 's1',
  groupId: 'g1',
  groupName: 'Film club',
  createdBy: makeUser({ id: 'u1', displayName: 'Ada Lovelace', username: 'ada' }),
  status: 'OPEN',
  strategy: 'MEAN',
  filters: {},
  effectiveFilter: makeEffectiveFilter(),
  members: [
    makeSessionMember({ id: 'u1', displayName: 'Ada Lovelace', username: 'ada' }),
    makeSessionMember({}, { voted: true }),
  ],
  results: [
    makeResult(),
    makeResult({ rank: 2, movie: makeMovie({ id: 200, title: 'Up' }), certification: 'PG', memberScores: { u1: 5, u2: 8 } }),
  ],
  pickedMovie: null,
  createdAt: '2026-10-16T19:00:00Z',
  ...overrides,
});

// ---- Phase 1b: onboarding, ratings, taste, "For you" -------------------------------------------

export const makeOnboardingState = (overrides: Partial<OnboardingStateDTO> = {}): OnboardingStateDTO => ({
  step: 1,
  completed: false,
  genrePrefs: [],
  moodPrefs: [],
  favorites: [],
  calibrationRatedCount: 0,
  comfort: makeComfort(),
  ...overrides,
});

export const makeRating = (overrides: Partial<RatingDTO> = {}): RatingDTO => ({
  id: 'r1',
  movie: makeMovie(),
  score: 8,
  body: null,
  containsSpoilers: false,
  visibility: 'FRIENDS',
  source: 'QUICK_RATE',
  watchedOn: null,
  createdAt: '2026-10-10T20:00:00Z',
  updatedAt: '2026-10-10T20:00:00Z',
  ...overrides,
});

export const makeDimension = (overrides: Partial<TasteDimensionDTO> = {}): TasteDimensionDTO => ({
  key: 'genre:878',
  label: 'Science Fiction',
  stated: 8,
  learned: 7.4,
  ...overrides,
});

export const makeTasteProfile = (overrides: Partial<TasteProfileDTO> = {}): TasteProfileDTO => ({
  status: 'FRESH',
  updatedAt: '2026-10-15T00:00:00Z',
  modelVersion: 'fallback-0',
  persona: 'Cozy Sci-Fi Romantic',
  topGenres: [makeDimension()],
  dimensions: [
    makeDimension(),
    makeDimension({ key: 'genre:27', label: 'Horror', stated: 4, learned: 7 }),
    makeDimension({ key: 'genre:35', label: 'Comedy', stated: null, learned: null }),
    makeDimension({ key: 'mood:cozy', label: 'Cozy', stated: 6, learned: 6.5 }),
    makeDimension({ key: 'mood:dark-gritty', label: 'Dark & gritty', stated: null, learned: 3.2 }),
  ],
  ratingsCount: 12,
  ...overrides,
});

export const makeHistoryEntry = (overrides: Partial<TasteHistoryEntryDTO> = {}): TasteHistoryEntryDTO => ({
  version: 1,
  reason: 'ONBOARDING',
  modelVersion: 'fallback-0',
  createdAt: '2026-10-15T00:00:00Z',
  topGenres: [makeDimension()],
  ...overrides,
});

export const makeRecommendation = (overrides: Partial<RecommendationDTO> = {}): RecommendationDTO => ({
  movie: makeMovie(),
  certification: 'R',
  score: 8.1,
  explanation: 'Because you love crime thrillers',
  ...overrides,
});

// ---- Phase 2b: ratings feed --------------------------------------------------------------------

export const makeFeedItem = (overrides: Partial<FeedItemDTO> = {}): FeedItemDTO => ({
  id: 'f1',
  author: makeUser({ friendship: 'FRIENDS' }),
  movie: makeMovie(),
  certification: 'R',
  score: 8,
  body: null,
  containsSpoilers: false,
  visibility: 'FRIENDS',
  likeCount: 0,
  likedByMe: false,
  commentCount: 0,
  mine: false,
  createdAt: '2026-10-10T20:00:00Z',
  updatedAt: '2026-10-10T20:00:00Z',
  ...overrides,
});

// ---- Phase 5: post-watch prompts, comments, notification preferences ---------------------------

export const makePendingRating = (overrides: Partial<PendingRatingDTO> = {}): PendingRatingDTO => ({
  movie: makeMovie(),
  sessionId: 's1',
  groupName: 'Film club',
  pickedAt: '2026-10-15T21:00:00Z',
  ...overrides,
});

export const makeComment = (overrides: Partial<CommentDTO> = {}): CommentDTO => ({
  id: 'c1',
  ratingId: 'f1',
  author: makeUser({ friendship: 'FRIENDS' }),
  body: 'Great call.',
  createdAt: '2026-10-10T21:00:00Z',
  mine: false,
  canDelete: false,
  ...overrides,
});

export const makeNotificationSettings = (
  overrides: Partial<NotificationSettingsDTO> = {},
): NotificationSettingsDTO => ({
  friendRequests: true,
  groupInvites: true,
  sessionStarted: true,
  comments: true,
  weeklyDigest: false,
  ...overrides,
});
