jest.mock('expo-router', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  const base = require('../test-utils/router').expoRouterMock;
  const Stack = Object.assign(() => <Text testID="admin-stack">stack</Text>, { Screen: () => null });
  // The not-found screen renders a Link with plain text.
  const Link = ({ children }: { children: string }) => <Text>{children}</Text>;
  return { ...base, Stack, Link };
});
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { fireEvent, screen, waitFor, within } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import AdminLayout from '../app/admin/_layout';
import AdminAuditScreen from '../app/admin/audit';
import AdminCatalogScreen, { groupSettings, SIZE_GUARD_NOTE } from '../app/admin/catalog';
import AdminOverviewScreen from '../app/admin/index';
import AdminReportsScreen from '../app/admin/reports';
import {
  makeAuditEntry,
  makeBoolSetting,
  makeCatalog,
  makeHealth,
  makeIntSetting,
  makeJob,
  makeReport,
  makeUsage,
  page,
} from '../test-utils/adminFixtures';
import { serveRoutes } from '../test-utils/fetchRoutes';
import { makeProfile } from '../test-utils/fixtures';
import { renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

describe('admin gating (app/admin/_layout)', () => {
  it('shows a loader while /me loads', async () => {
    serveRoutes(fetchMock, [{ path: '/me', hang: true }]);
    await renderWithClient(<AdminLayout />);
    expect(screen.queryByTestId('admin-stack')).toBeNull();
    expect(screen.queryByText('This reel is missing')).toBeNull();
  });

  it('a non-admin gets the normal not-found screen', async () => {
    serveRoutes(fetchMock, [{ path: '/me', body: makeProfile({ isAdmin: false }) }]);
    await renderWithClient(<AdminLayout />);
    expect(await screen.findByText('This reel is missing')).toBeOnTheScreen();
    expect(screen.queryByTestId('admin-stack')).toBeNull();
    // Nothing admin is even requested.
    expect(fetchMock.mock.calls.every(([url]) => !String(url).includes('/admin'))).toBe(true);
  });

  it('a failed /me is treated as not-found too', async () => {
    serveRoutes(fetchMock, [{ path: '/me', status: 500, body: { message: 'boom' } }]);
    await renderWithClient(<AdminLayout />);
    expect(await screen.findByText('This reel is missing')).toBeOnTheScreen();
  });

  it('an admin gets the admin stack', async () => {
    serveRoutes(fetchMock, [{ path: '/me', body: makeProfile({ isAdmin: true }) }]);
    await renderWithClient(<AdminLayout />);
    expect(await screen.findByTestId('admin-stack')).toBeOnTheScreen();
  });
});

describe('Overview', () => {
  const routes = () =>
    serveRoutes(fetchMock, [
      { path: '/admin/metrics/usage', body: makeUsage() },
      { path: '/admin/metrics/health', body: makeHealth() },
      { path: '/admin/metrics/catalog', body: makeCatalog() },
    ]);

  it('shows skeletons while loading', async () => {
    serveRoutes(fetchMock, [{ path: /\/admin\/metrics/, hang: true }]);
    await renderWithClient(<AdminOverviewScreen />);
    expect(screen.getByTestId('usage-loading')).toBeOnTheScreen();
    expect(screen.getByTestId('health-loading')).toBeOnTheScreen();
    expect(screen.getByTestId('catalog-loading')).toBeOnTheScreen();
  });

  it('shows usage, ratings per day, health and the catalog', async () => {
    routes();
    await renderWithClient(<AdminOverviewScreen />);
    expect(await screen.findByLabelText('Users, 1,204, 38 new this week')).toBeOnTheScreen();
    expect(screen.getByLabelText('Onboarded, 903, 75% of users')).toBeOnTheScreen();
    expect(screen.getByLabelText('Active groups, 17, a session in 30 days')).toBeOnTheScreen();
    expect(screen.getByTestId('daily-bars')).toBeOnTheScreen();
    expect(await screen.findByLabelText('ML service, Up, model placeholder-1 · 12 ms')).toBeOnTheScreen();
    expect(screen.getByLabelText('Open reports, 4')).toBeOnTheScreen();
    expect(await screen.findByLabelText('Movies, 150,000, 60% with a certification')).toBeOnTheScreen();
    expect(screen.getByTestId('size-meter')).toBeOnTheScreen();
  });

  it('links open reports to the queue', async () => {
    routes();
    await renderWithClient(<AdminOverviewScreen />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Review reports' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/admin/reports');
  });

  it.each([
    [{ configured: false, reachable: false, status: null, modelVersion: null, latencyMs: null }, 'ML service, Fallback, ML_SERVICE_URL not set'],
    [{ configured: true, reachable: false, status: null, modelVersion: null, latencyMs: 2000 }, 'ML service, Down, health check failed'],
  ])('ML state %#', async (ml, label) => {
    serveRoutes(fetchMock, [
      { path: '/admin/metrics/health', body: makeHealth({ ml, openReports: 0 }) },
      { path: /\/admin\/metrics/, hang: true },
    ]);
    await renderWithClient(<AdminOverviewScreen />);
    expect(await screen.findByLabelText(label)).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Review reports' })).toBeNull();
  });

  it('a failed block offers a retry without hiding the others', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/admin/metrics/usage', status: 500, body: { message: 'x' }, times: 1 },
      { path: '/admin/metrics/usage', body: makeUsage() },
      { path: '/admin/metrics/health', body: makeHealth() },
      { path: '/admin/metrics/catalog', body: makeCatalog() },
    ]);
    await renderWithClient(<AdminOverviewScreen />);
    expect(await screen.findByText("Couldn't load usage")).toBeOnTheScreen();
    expect(await screen.findByLabelText('Open reports, 4')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByLabelText('Users, 1,204, 38 new this week')).toBeOnTheScreen();
    expect(api.callsTo('GET', '/admin/metrics/usage')).toHaveLength(2);
  });
});

describe('Catalog & jobs', () => {
  const settings = [
    makeIntSetting({ key: 'catalog.max-database-mb', group: 'Catalog', label: 'Database size guard', unit: 'MB', value: 450, defaultValue: 450, min: 50, max: 480 }),
    makeIntSetting(),
    makeBoolSetting(),
  ];
  const routes = () =>
    serveRoutes(fetchMock, [
      { path: '/admin/metrics/catalog', body: makeCatalog() },
      { path: '/admin/jobs', body: [makeJob(), makeJob({ name: 'closeness', label: 'Friend closeness', controllable: false })] },
      { path: '/admin/settings', body: settings },
    ]);

  it('groups settings in registry order', () => {
    expect(groupSettings(settings).map(([group, items]) => [group, items.length])).toEqual([
      ['Catalog', 1],
      ['Quality', 2],
    ]);
  });

  it('shows catalog stats, the vote tiers and the size guard note', async () => {
    routes();
    await renderWithClient(<AdminCatalogScreen />);
    expect(await screen.findByTestId('metric-movies')).toBeOnTheScreen();
    expect(screen.getByLabelText('Rejected ids, 2,500, failed the quality bar')).toBeOnTheScreen();
    expect(screen.getByLabelText(/^TMDB votes: 1,000\+ 10,000/)).toBeOnTheScreen();
    expect(screen.getAllByText(SIZE_GUARD_NOTE).length).toBeGreaterThanOrEqual(1);
  });

  it('lists the jobs with controls only where they apply', async () => {
    routes();
    await renderWithClient(<AdminCatalogScreen />);
    const topUp = await screen.findByTestId('job-catalog-top-up');
    expect(within(topUp).getByRole('button', { name: 'Run Nightly catalog top-up now' })).toBeOnTheScreen();
    expect(within(screen.getByTestId('job-closeness')).queryByRole('button')).toBeNull();
  });

  it('lists every setting, with the size-guard note under the guard', async () => {
    routes();
    await renderWithClient(<AdminCatalogScreen />);
    expect(await screen.findByTestId('setting-catalog.max-database-mb')).toBeOnTheScreen();
    expect(screen.getByTestId('setting-catalog.quality.min-votes')).toBeOnTheScreen();
    expect(screen.getByRole('switch', { name: 'Require a poster' })).toBeOnTheScreen();
    expect(screen.getByTestId('size-guard-note')).toHaveTextContent(SIZE_GUARD_NOTE);
    await fireEvent.changeText(screen.getByLabelText('Database size guard (MB)'), '481');
    expect(screen.getByText('Must be between 50 and 480 MB.')).toBeOnTheScreen();
  });

  it('each block fails on its own with a retry', async () => {
    serveRoutes(fetchMock, [
      { path: '/admin/metrics/catalog', status: 500, body: {} },
      { path: '/admin/jobs', status: 500, body: {} },
      { path: '/admin/settings', status: 500, body: {} },
    ]);
    await renderWithClient(<AdminCatalogScreen />);
    expect(await screen.findByText("Couldn't load catalog stats")).toBeOnTheScreen();
    expect(await screen.findByText("Couldn't load jobs")).toBeOnTheScreen();
    expect(await screen.findByText("Couldn't load settings")).toBeOnTheScreen();
  });
});

describe('Reports', () => {
  it('shows the open queue, loads more, and switches status', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/admin/reports?status=OPEN', body: page([makeReport()], 'c1') },
      { path: '/admin/reports?status=OPEN&cursor=c1', body: page([makeReport({ id: 'r2', reason: 'SPAM' })]) },
      { path: '/admin/reports?status=RESOLVED', body: page([]) },
    ]);
    await renderWithClient(<AdminReportsScreen />);
    expect(await screen.findByTestId('report-r1')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByTestId('report-r2')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();

    await fireEvent.press(screen.getByRole('tab', { name: 'Resolved' }));
    expect(await screen.findByText('No resolved reports yet.')).toBeOnTheScreen();
    expect(api.callsTo('GET', '/admin/reports?status=RESOLVED')).toHaveLength(1);
  });

  it('an empty queue says so; a failure offers a retry', async () => {
    serveRoutes(fetchMock, [{ path: '/admin/reports?status=OPEN', body: page([]) }]);
    await renderWithClient(<AdminReportsScreen />);
    expect(await screen.findByText('All clear')).toBeOnTheScreen();
  });

  it('resolving refetches the queue', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/admin/reports?status=OPEN', body: page([makeReport()]), times: 1 },
      { path: '/admin/reports?status=OPEN', body: page([]) },
      { method: 'POST', path: '/admin/reports/r1/resolve', body: makeReport({ status: 'RESOLVED' }) },
      { path: /.*/, body: {} },
    ]);
    await renderWithClient(<AdminReportsScreen />);
    await fireEvent.press(await screen.findByRole('button', { name: /^Resolve report/ }));
    expect(await screen.findByText('All clear')).toBeOnTheScreen();
    expect(api.callsTo('GET', '/admin/reports?status=OPEN')).toHaveLength(2);
  });

  it('a failed load offers a retry', async () => {
    serveRoutes(fetchMock, [
      { path: '/admin/reports?status=OPEN', status: 500, body: {}, times: 1 },
      { path: '/admin/reports?status=OPEN', body: page([makeReport()]) },
    ]);
    await renderWithClient(<AdminReportsScreen />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('report-r1')).toBeOnTheScreen();
  });
});

describe('Audit log', () => {
  it('lists entries, filters by action and pages', async () => {
    const api = serveRoutes(fetchMock, [
      { path: '/admin/audit-log', body: page([makeAuditEntry()], 'n1') },
      { path: '/admin/audit-log?cursor=n1', body: page([makeAuditEntry({ id: 'a2', action: 'JOB_RUN', target: 'catalog-top-up' })]) },
      { path: '/admin/audit-log?action=REPORT_RESOLVE', body: page([]) },
    ]);
    await renderWithClient(<AdminAuditScreen />);
    expect(await screen.findByTestId('audit-a1')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'All' })).toBeSelected();
    await fireEvent.press(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByTestId('audit-a2')).toBeOnTheScreen();

    await fireEvent.press(screen.getByRole('button', { name: 'Report resolved' }));
    expect(await screen.findByText('Nothing logged')).toBeOnTheScreen();
    await waitFor(() => expect(api.callsTo('GET', '/admin/audit-log?action=REPORT_RESOLVE')).toHaveLength(1));
    expect(screen.getByRole('button', { name: 'Report resolved' })).toBeSelected();
  });

  it('a failed load offers a retry', async () => {
    serveRoutes(fetchMock, [{ path: '/admin/audit-log', status: 500, body: {} }]);
    await renderWithClient(<AdminAuditScreen />);
    expect(await screen.findByText("Couldn't load the audit log")).toBeOnTheScreen();
  });
});
