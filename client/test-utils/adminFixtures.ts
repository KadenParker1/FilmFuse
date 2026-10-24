import type {
  AdminAuditEntryDTO,
  AdminJobDTO,
  AdminReportDTO,
  AdminSettingDTO,
  CatalogMetricsDTO,
  DailyCountDTO,
  HealthMetricsDTO,
  JobRunDTO,
  PageDTO,
  UsageMetricsDTO,
} from '../types/api';
import { queryKeys } from '../lib/queryKeys';
import { makeProfile } from './fixtures';
import { createTestQueryClient } from './render';

export const page = <T>(items: T[], nextCursor: string | null = null): PageDTO<T> => ({ items, nextCursor });

export const makeIntSetting = (overrides: Partial<AdminSettingDTO> = {}): AdminSettingDTO => ({
  key: 'catalog.quality.min-votes',
  group: 'Quality',
  label: 'Minimum TMDB votes',
  description: 'Long-tail movies with fewer votes are not stored.',
  unit: 'votes',
  type: 'INTEGER',
  value: 5,
  defaultValue: 5,
  overridden: false,
  min: 0,
  max: 100,
  updatedAt: null,
  updatedBy: null,
  ...overrides,
});

export const makeBoolSetting = (overrides: Partial<AdminSettingDTO> = {}): AdminSettingDTO => ({
  key: 'catalog.quality.require-poster',
  group: 'Quality',
  label: 'Require a poster',
  description: 'Long-tail movies without a poster are not stored.',
  unit: null,
  type: 'BOOLEAN',
  value: true,
  defaultValue: true,
  overridden: false,
  min: null,
  max: null,
  updatedAt: null,
  updatedBy: null,
  ...overrides,
});

export const makeRun = (overrides: Partial<JobRunDTO> = {}): JobRunDTO => ({
  id: 7,
  startedAt: '2026-10-23T04:00:00Z',
  finishedAt: '2026-10-23T04:03:05Z',
  outcome: 'COMPLETED',
  summary: { fetched: 120, failed: 0 },
  triggeredBy: null,
  ...overrides,
});

export const makeJob = (overrides: Partial<AdminJobDTO> = {}): AdminJobDTO => ({
  name: 'catalog-top-up',
  label: 'Nightly catalog top-up',
  scheduled: true,
  paused: false,
  controllable: true,
  schedule: '0 0 4 * * *',
  nextRunAt: '2026-10-24T04:00:00Z',
  running: false,
  lastRunStale: false,
  lastRun: makeRun(),
  recentRuns: [makeRun()],
  ...overrides,
});

export const makeReport = (overrides: Partial<AdminReportDTO> = {}): AdminReportDTO => ({
  id: 'r1',
  targetType: 'RATING',
  targetId: 't1',
  reason: 'SPOILERS',
  details: 'ruins the ending',
  status: 'OPEN',
  createdAt: '2026-10-23T10:00:00Z',
  reporter: { id: 'u9', username: 'grace', displayName: 'Grace Hopper' },
  target: {
    exists: true,
    author: { id: 'u2', username: 'alan', displayName: 'Alan Turing' },
    text: 'The twist is that…',
    movieId: 550,
    movieTitle: 'Fight Club',
    score: 9,
    authorStatus: 'ACTIVE',
  },
  openReportsOnTarget: 1,
  resolvedAt: null,
  resolvedBy: null,
  resolutionNote: null,
  ...overrides,
});

export const makeAuditEntry = (overrides: Partial<AdminAuditEntryDTO> = {}): AdminAuditEntryDTO => ({
  id: 'a1',
  admin: { id: 'u1', username: 'ada', displayName: 'Ada Lovelace' },
  action: 'SETTING_UPDATE',
  target: 'catalog.quality.min-votes',
  oldValue: 5,
  newValue: 12,
  createdAt: '2026-10-23T10:00:00Z',
  ...overrides,
});

export const days = (counts: number[], last = '2026-10-23'): DailyCountDTO[] => {
  const end = new Date(`${last}T00:00:00Z`).getTime();
  return counts.map((count, i) => ({
    date: new Date(end - (counts.length - 1 - i) * 86_400_000).toISOString().slice(0, 10),
    count,
  }));
};

export const makeUsage = (overrides: Partial<UsageMetricsDTO> = {}): UsageMetricsDTO => ({
  usersTotal: 1204,
  usersNew7d: 38,
  usersOnboarded: 903,
  ratingsPerDay: days(Array.from({ length: 30 }, (_, i) => (i === 29 ? 12 : i))),
  sessionsStarted7d: 14,
  sessionsStarted30d: 60,
  sessionsPicked7d: 9,
  sessionsPicked30d: 41,
  activeGroups30d: 17,
  generatedAt: '2026-10-23T10:00:00Z',
  ...overrides,
});

export const makeHealth = (overrides: Partial<HealthMetricsDTO> = {}): HealthMetricsDTO => ({
  ml: { configured: true, reachable: true, status: 'ok', modelVersion: 'placeholder-1', latencyMs: 12 },
  vectorsDirty: 3,
  vectorsDirtyOldest: null,
  vectorsNeverBuilt: 2,
  openReports: 4,
  generatedAt: '2026-10-23T10:00:00Z',
  ...overrides,
});

const MB = 1024 * 1024;

export const makeCatalog = (overrides: Partial<CatalogMetricsDTO> = {}): CatalogMetricsDTO => ({
  totalMovies: 150_000,
  withCertification: 90_000,
  voteTiers: { votes1000Plus: 10_000, votes100To999: 40_000, votes20To99: 50_000, votesUnder20: 45_000, votesUnknown: 5_000 },
  rejected: 2_500,
  databaseBytes: 200 * MB,
  maxDatabaseMb: 450,
  maxDatabaseMbCeiling: 480,
  planLimitMb: 500,
  generatedAt: '2026-10-23T10:00:00Z',
  ...overrides,
});

/** A test client whose cached `/me` is an admin, as under the admin layout (admin queries need it). */
export function adminClient() {
  const client = createTestQueryClient();
  client.setQueryData(queryKeys.me, makeProfile({ isAdmin: true }));
  return client;
}
