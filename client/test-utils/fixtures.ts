import type {
  FriendDTO,
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
