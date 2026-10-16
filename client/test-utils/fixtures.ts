import type { ProfileResponseDTO } from '../types/api';

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
