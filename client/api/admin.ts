import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type {
  AdminAction,
  AdminAuditEntryDTO,
  AdminJobDTO,
  AdminReportDTO,
  AdminSettingDTO,
  CatalogMetricsDTO,
  HealthMetricsDTO,
  JobRunStartedDTO,
  PageDTO,
  ReportStatus,
  ResolveReportDTO,
  UsageMetricsDTO,
} from '../types/api';

/*
 * Admin portal hooks. Every endpoint is admin-only server-side (non-admins get a 404); the client
 * only shows the portal when `me.isAdmin`, and never relies on that for security.
 */

/** Metrics are cached ~30 s server-side; refetching more often than that only re-reads the cache. */
const METRICS_STALE_MS = 30_000;

export const fetchCatalogMetrics = () => apiClient.get<CatalogMetricsDTO>('/admin/metrics/catalog');
export const fetchUsageMetrics = () => apiClient.get<UsageMetricsDTO>('/admin/metrics/usage');
export const fetchHealthMetrics = () => apiClient.get<HealthMetricsDTO>('/admin/metrics/health');
export const fetchAdminSettings = () => apiClient.get<AdminSettingDTO[]>('/admin/settings');
export const fetchAdminReports = (status: ReportStatus, cursor?: string | null) =>
  apiClient.get<PageDTO<AdminReportDTO>>(withQuery('/admin/reports', { status, cursor }));
export const fetchAdminAudit = (action: AdminAction | null, cursor?: string | null) =>
  apiClient.get<PageDTO<AdminAuditEntryDTO>>(withQuery('/admin/audit-log', { action, cursor }));

export const fetchAdminJobs = () => apiClient.get<AdminJobDTO[]>('/admin/jobs');

const settingPath = (key: string) => `/admin/settings/${encodeURIComponent(key)}`;

export function useCatalogMetrics() {
  return useQuery({ queryKey: queryKeys.adminCatalogMetrics, queryFn: fetchCatalogMetrics, staleTime: METRICS_STALE_MS });
}

export function useUsageMetrics() {
  return useQuery({ queryKey: queryKeys.adminUsageMetrics, queryFn: fetchUsageMetrics, staleTime: METRICS_STALE_MS });
}

export function useHealthMetrics() {
  return useQuery({ queryKey: queryKeys.adminHealthMetrics, queryFn: fetchHealthMetrics, staleTime: METRICS_STALE_MS });
}

/** Every runtime setting with its effective value, default and bounds. */
export function useAdminSettings() {
  return useQuery({ queryKey: queryKeys.adminSettings, queryFn: fetchAdminSettings });
}

/** Replaces one setting in the cached list with the server's answer. */
function storeSetting(settings: AdminSettingDTO[] | undefined, updated: AdminSettingDTO) {
  return settings?.map((s) => (s.key === updated.key ? updated : s));
}

/** PUT /admin/settings/{key}; a 400 carries `fieldErrors.value` (type / bounds). Audited server-side. */
export function useUpdateAdminSetting() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ key, value }: { key: string; value: number | boolean }) =>
      apiClient.put<AdminSettingDTO>(settingPath(key), { value }),
    onSuccess: (updated) => {
      qc.setQueryData<AdminSettingDTO[]>(queryKeys.adminSettings, (old) => storeSetting(old, updated));
      void qc.invalidateQueries({ queryKey: queryKeys.adminAuditAll });
      void qc.invalidateQueries({ queryKey: queryKeys.adminCatalogMetrics });
    },
  });
}

/** DELETE /admin/settings/{key}: back to the configured default. */
export function useResetAdminSetting() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (key: string) => apiClient.del<AdminSettingDTO>(settingPath(key)),
    onSuccess: (updated) => {
      qc.setQueryData<AdminSettingDTO[]>(queryKeys.adminSettings, (old) => storeSetting(old, updated));
      void qc.invalidateQueries({ queryKey: queryKeys.adminAuditAll });
      void qc.invalidateQueries({ queryKey: queryKeys.adminCatalogMetrics });
    },
  });
}

/** The reports queue for one status (OPEN oldest first; closed ones newest first). */
export function useAdminReports(status: ReportStatus) {
  return useInfiniteQuery({
    queryKey: queryKeys.adminReports(status),
    queryFn: ({ pageParam }) => fetchAdminReports(status, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

/** Resolves or dismisses an OPEN report (409 if someone already did). Refreshes every queue and the counts. */
export function useResolveReport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: ResolveReportDTO & { id: string }) => {
      const note = body.note?.trim();
      return apiClient.post<AdminReportDTO>(`/admin/reports/${encodeURIComponent(id)}/resolve`, {
        resolution: body.resolution,
        note: note ? note : null,
      });
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.adminReportsAll });
      void qc.invalidateQueries({ queryKey: queryKeys.adminHealthMetrics });
      void qc.invalidateQueries({ queryKey: queryKeys.adminAuditAll });
    },
  });
}

/** The audit log, newest first; null = every action. */
export function useAdminAudit(action: AdminAction | null) {
  return useInfiniteQuery({
    queryKey: queryKeys.adminAudit(action),
    queryFn: ({ pageParam }) => fetchAdminAudit(action, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

/** While a job runs, poll its status every few seconds; otherwise stay put. */
export const JOBS_POLL_MS = 5_000;

/** Every background job with its schedule and recent runs; polls while one is running. */
export function useAdminJobs() {
  return useQuery({
    queryKey: queryKeys.adminJobs,
    queryFn: fetchAdminJobs,
    refetchInterval: (query) => (query.state.data?.some((job) => job.running) ? JOBS_POLL_MS : false),
  });
}

const jobPath = (name: string, action: 'run' | 'pause' | 'resume') =>
  `/admin/jobs/${encodeURIComponent(name)}/${action}`;

/** Run now: 202 with the run id; a 409 means a catalog job is already running. */
export function useRunJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => apiClient.post<JobRunStartedDTO>(jobPath(name, 'run')),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.adminJobs });
      void qc.invalidateQueries({ queryKey: queryKeys.adminAuditAll });
    },
  });
}

/** Pause (paused = true) or resume a job's scheduled runs; stores the server's answer. */
export function useSetJobPaused() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, paused }: { name: string; paused: boolean }) =>
      apiClient.post<AdminJobDTO>(jobPath(name, paused ? 'pause' : 'resume')),
    onSuccess: (job) => {
      qc.setQueryData<AdminJobDTO[]>(queryKeys.adminJobs, (old) => old?.map((j) => (j.name === job.name ? job : j)));
      void qc.invalidateQueries({ queryKey: queryKeys.adminSettings });
      void qc.invalidateQueries({ queryKey: queryKeys.adminAuditAll });
    },
  });
}
