jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import {
  JOBS_POLL_MS,
  useAdminAudit,
  useAdminJobs,
  useAdminReports,
  useAdminSettings,
  useCatalogMetrics,
  useHealthMetrics,
  useResetAdminSetting,
  useResolveReport,
  useRunJob,
  useSetJobPaused,
  useUpdateAdminSetting,
  useUsageMetrics,
} from '../api/admin';
import { queryKeys } from '../lib/queryKeys';
import { makeCatalog, makeHealth, makeIntSetting, makeJob, makeReport, makeUsage, page } from '../test-utils/adminFixtures';
import { API_BASE, serveRoutes } from '../test-utils/fetchRoutes';
import { renderHookWithClient } from '../test-utils/render';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

describe('admin query hooks', () => {
  it.each([
    ['catalog', useCatalogMetrics, '/admin/metrics/catalog', makeCatalog()],
    ['usage', useUsageMetrics, '/admin/metrics/usage', makeUsage()],
    ['health', useHealthMetrics, '/admin/metrics/health', makeHealth()],
    ['settings', useAdminSettings, '/admin/settings', [makeIntSetting()]],
    ['jobs', useAdminJobs, '/admin/jobs', [makeJob()]],
  ])('%s GETs its endpoint with the bearer token', async (_name, hook, path, body) => {
    serveRoutes(fetchMock, [{ path, body }]);
    const { result } = await renderHookWithClient(() => (hook as () => { data?: unknown; isSuccess: boolean })());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(body);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe(`${API_BASE}${path}`);
    expect(init.headers.Authorization).toBe('Bearer tok');
  });

  it('reports page by status and cursor', async () => {
    serveRoutes(fetchMock, [
      { path: '/admin/reports?status=DISMISSED', body: page([makeReport()], 'c 1') },
      { path: '/admin/reports?status=DISMISSED&cursor=c%201', body: page([]) },
    ]);
    const { result } = await renderHookWithClient(() => useAdminReports('DISMISSED'));
    await waitFor(() => expect(result.current.hasNextPage).toBe(true));
    await act(() => result.current.fetchNextPage());
    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2));
    expect(result.current.hasNextPage).toBe(false);
  });

  it('the audit log filters by action (none = every action)', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/admin/audit-log?action=JOB_PAUSE', body: page([]) },
      { path: '/admin/audit-log', body: page([]) },
    ]);
    const filtered = await renderHookWithClient(() => useAdminAudit('JOB_PAUSE'));
    await waitFor(() => expect(filtered.result.current.isSuccess).toBe(true));
    const all = await renderHookWithClient(() => useAdminAudit(null));
    await waitFor(() => expect(all.result.current.isSuccess).toBe(true));
    expect(api.calls().map((c) => c.path)).toEqual(['/admin/audit-log?action=JOB_PAUSE', '/admin/audit-log']);
  });

  it('jobs poll only while one is running', async () => {
    jest.useFakeTimers();
    try {
      const api = serveRoutes(fetchMock, [
        { path: '/admin/jobs', body: [makeJob({ running: true })], times: 1 },
        { path: '/admin/jobs', body: [makeJob({ running: false })] },
      ]);
      const { result } = await renderHookWithClient(() => useAdminJobs());
      await waitFor(() => expect(result.current.data?.[0]?.running).toBe(true));
      await act(async () => {
        jest.advanceTimersByTime(JOBS_POLL_MS + 10);
      });
      await waitFor(() => expect(result.current.data?.[0]?.running).toBe(false));
      await act(async () => {
        jest.advanceTimersByTime(JOBS_POLL_MS * 3);
      });
      expect(api.callsTo('GET', '/admin/jobs')).toHaveLength(2);
    } finally {
      jest.useRealTimers();
    }
  });
});

describe('admin mutations', () => {
  it('updating a setting PUTs the value to the encoded key and stores the answer', async () => {
    const setting = makeIntSetting();
    const updated = { ...setting, value: 9, overridden: true };
    const api = serveRoutes(fetchMock, [{ method: 'PUT', path: '/admin/settings/catalog.quality.min-votes', body: updated }]);
    const { result, client } = await renderHookWithClient(() => useUpdateAdminSetting());
    client.setQueryData(queryKeys.adminSettings, [setting, makeIntSetting({ key: 'other' })]);
    await act(() => result.current.mutateAsync({ key: setting.key, value: 9 }));
    expect(api.calls()[0]!.body).toEqual({ value: 9 });
    expect(client.getQueryData(queryKeys.adminSettings)).toEqual([updated, makeIntSetting({ key: 'other' })]);
  });

  it('resetting DELETEs the setting and stores the default', async () => {
    const setting = makeIntSetting({ value: 30, overridden: true });
    serveRoutes(fetchMock, [{ method: 'DELETE', path: '/admin/settings/catalog.quality.min-votes', body: makeIntSetting() }]);
    const { result, client } = await renderHookWithClient(() => useResetAdminSetting());
    client.setQueryData(queryKeys.adminSettings, [setting]);
    await act(() => result.current.mutateAsync(setting.key));
    expect(client.getQueryData(queryKeys.adminSettings)).toEqual([makeIntSetting()]);
  });

  it('resolving trims the note (blank = null) and refreshes the queues and counts', async () => {
    const api = serveRoutes(fetchMock, [{ method: 'POST', path: '/admin/reports/r%201/resolve', body: makeReport() }]);
    const { result, client } = await renderHookWithClient(() => useResolveReport());
    const spy = jest.spyOn(client, 'invalidateQueries');
    await act(() => result.current.mutateAsync({ id: 'r 1', resolution: 'DISMISSED', note: '   ' }));
    expect(api.calls()[0]!.body).toEqual({ resolution: 'DISMISSED', note: null });
    expect(spy).toHaveBeenCalledWith({ queryKey: queryKeys.adminReportsAll });
    expect(spy).toHaveBeenCalledWith({ queryKey: queryKeys.adminHealthMetrics });
    expect(spy).toHaveBeenCalledWith({ queryKey: queryKeys.adminAuditAll });
  });

  it('run now POSTs and refreshes the jobs; pause and resume store the job', async () => {
    const api = serveRoutes(fetchMock, [
      { method: 'POST', path: '/admin/jobs/catalog-refresh/run', status: 202, body: { jobName: 'catalog-refresh', runId: 3, triggeredBy: 'admin:u1' } },
      { method: 'POST', path: '/admin/jobs/catalog-refresh/pause', body: makeJob({ name: 'catalog-refresh', paused: true }) },
      { method: 'POST', path: '/admin/jobs/catalog-refresh/resume', body: makeJob({ name: 'catalog-refresh', paused: false }) },
    ]);
    const run = await renderHookWithClient(() => useRunJob());
    const spy = jest.spyOn(run.client, 'invalidateQueries');
    await act(() => run.result.current.mutateAsync('catalog-refresh'));
    expect(spy).toHaveBeenCalledWith({ queryKey: queryKeys.adminJobs });

    const paused = await renderHookWithClient(() => useSetJobPaused());
    paused.client.setQueryData(queryKeys.adminJobs, [makeJob(), makeJob({ name: 'catalog-refresh' })]);
    await act(() => paused.result.current.mutateAsync({ name: 'catalog-refresh', paused: true }));
    expect(paused.client.getQueryData<ReturnType<typeof makeJob>[]>(queryKeys.adminJobs)?.[1]?.paused).toBe(true);
    await act(() => paused.result.current.mutateAsync({ name: 'catalog-refresh', paused: false }));
    expect(api.calls().map((c) => `${c.method} ${c.path}`)).toEqual([
      'POST /admin/jobs/catalog-refresh/run',
      'POST /admin/jobs/catalog-refresh/pause',
      'POST /admin/jobs/catalog-refresh/resume',
    ]);
  });
});
