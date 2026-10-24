jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { fireEvent, render, screen, within } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { Text } from 'react-native';
import { AdminFrame, ADMIN_SECTIONS } from '../components/admin/AdminFrame';
import { AuditRow } from '../components/admin/AuditRow';
import { DailyBars, ShareBar, SizeMeter } from '../components/admin/Charts';
import { JobCard, jobStatus } from '../components/admin/JobCard';
import { MetricCard } from '../components/admin/MetricCard';
import { ReportCard } from '../components/admin/ReportCard';
import { SettingField } from '../components/admin/SettingField';
import {
  days,
  makeAuditEntry,
  makeBoolSetting,
  makeIntSetting,
  makeJob,
  makeReport,
  makeRun,
} from '../test-utils/adminFixtures';
import { serveRoutes } from '../test-utils/fetchRoutes';
import { renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';
import { queryKeys } from '../lib/queryKeys';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

const MB = 1024 * 1024;

describe('AdminFrame', () => {
  it('shows the title, the sections with the current one selected, and switches with replace', async () => {
    await render(
      <AdminFrame current="catalog" title="Catalog & jobs">
        <Text>body</Text>
      </AdminFrame>,
    );
    expect(screen.getByRole('header', { name: 'Catalog & jobs' })).toBeOnTheScreen();
    expect(screen.getByText('body')).toBeOnTheScreen();
    expect(screen.getByRole('tab', { name: 'Catalog & jobs' })).toBeSelected();
    expect(screen.getByRole('tab', { name: 'Overview' })).not.toBeSelected();
    await fireEvent.press(screen.getByRole('tab', { name: 'Reports' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/admin/reports');
    await fireEvent.press(screen.getByRole('tab', { name: 'Catalog & jobs' }));
    expect(routerState.router.replace).toHaveBeenCalledTimes(1);
    expect(ADMIN_SECTIONS.map((s) => s.href)).toEqual(['/admin', '/admin/catalog', '/admin/reports', '/admin/audit']);
  });

  it('Back falls back to the profile', async () => {
    routerState.router.canGoBack.mockReturnValue(false);
    await render(
      <AdminFrame current="overview" title="Overview">
        <Text>x</Text>
      </AdminFrame>,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/profile');
  });
});

describe('MetricCard', () => {
  it('reads as one element with label, value and hint', async () => {
    await render(<MetricCard testID="m" label="Users" value="1,204" hint="38 new this week" tone="good" />);
    expect(screen.getByTestId('m').props.accessibilityLabel).toBe('Users, 1,204, 38 new this week');
    expect(screen.getByText('1,204')).toBeOnTheScreen();
  });
});

describe('charts', () => {
  it('DailyBars draws one bar per day scaled to the peak, with a spoken summary', async () => {
    const data = days([0, 5, 10]);
    await render(<DailyBars data={data} label="Ratings per day" />);
    const chart = screen.getByTestId('daily-bars');
    expect(chart.props.accessibilityLabel).toBe('Ratings per day: 15 in 3 days, busiest Oct 23 with 10, today 10.');
    expect(screen.getByTestId(`bar-${data[2]!.date}`).props.style).toEqual({ height: 120 });
    expect(screen.getByTestId(`bar-${data[1]!.date}`).props.style).toEqual({ height: 60 });
    expect(screen.getByTestId(`bar-${data[0]!.date}`).props.style).toEqual({ height: 1 });
    expect(screen.getByText('peak 10')).toBeOnTheScreen();
    expect(screen.getByText('Oct 21')).toBeOnTheScreen();
  });

  it('DailyBars handles an all-zero month', async () => {
    await render(<DailyBars data={days([0, 0])} label="Ratings" />);
    expect(screen.getByTestId('daily-bars').props.accessibilityLabel).toBe('Ratings: 0 in 2 days, today 0.');
  });

  it('ShareBar sizes segments by value and lists the numbers', async () => {
    await render(
      <ShareBar
        label="Votes"
        shares={[
          { label: 'A', value: 3, color: 'accent' },
          { label: 'B', value: 1, color: 'danger' },
          { label: 'C', value: 0, color: 'muted' },
        ]}
      />,
    );
    expect(screen.getByLabelText('Votes: A 3, B 1, C 0.')).toBeOnTheScreen();
    expect(screen.getByText(/3 · 75%/, { includeHiddenElements: true })).toBeOnTheScreen();
    expect(screen.getByText(/0 · 0%/, { includeHiddenElements: true })).toBeOnTheScreen();
    expect(screen.getByTestId('share-A').props.style).toMatchObject({ width: '75%' });
    expect(screen.queryByTestId('share-C')).toBeNull();
  });

  it.each([
    [100, 'bg-success'],
    [400, 'bg-accent2'],
    [460, 'bg-danger'],
  ])('SizeMeter at %i MB of a 450 MB guard is %s', async (mb, tone) => {
    await render(<SizeMeter bytes={mb * MB} guardMb={450} planMb={500} />);
    expect(screen.getByTestId('size-meter-fill').props.className).toContain(tone);
    expect(screen.getByTestId('size-meter').props.accessibilityLabel).toBe(
      `Database ${mb.toFixed(1)} MB of a 450 MB guard and a 500 MB plan limit.`,
    );
    expect(screen.getByTestId('size-meter-guard').props.style).toEqual({ left: '90%' });
  });
});

describe('SettingField — numbers', () => {
  const setting = makeIntSetting();
  const PATH = '/admin/settings/catalog.quality.min-votes';
  const field = () => screen.getByLabelText('Minimum TMDB votes (votes)');

  it('shows the value, default and bounds; Save is off until something changes', async () => {
    await renderWithClient(<SettingField setting={setting} />);
    expect(field().props.value).toBe('5');
    expect(screen.getByText('Default 5 votes · allowed 0–100 votes')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Save Minimum TMDB votes' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Reset/ })).toBeNull();
  });

  it('validates inline as you type and never sends an invalid value', async () => {
    const api = serveRoutes(fetchMock, []);
    await renderWithClient(<SettingField setting={setting} />);
    await fireEvent.changeText(field(), '101');
    expect(screen.getByText('Must be between 0 and 100 votes.')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Save Minimum TMDB votes' })).toBeDisabled();
    await fireEvent(field(), 'submitEditing');
    await fireEvent.changeText(field(), '4.5');
    expect(screen.getByText('Must be a whole number.')).toBeOnTheScreen();
    expect(api.calls()).toHaveLength(0);
  });

  it('saves a valid value, stores the answer in the settings cache and confirms', async () => {
    const api = serveRoutes(fetchMock, [
      { method: 'PUT', path: PATH, body: { ...setting, value: 12, overridden: true } },
    ]);
    const { client } = await renderWithClient(<SettingField setting={setting} />);
    client.setQueryData(queryKeys.adminSettings, [setting]);
    await fireEvent.changeText(field(), ' 12 ');
    await fireEvent.press(screen.getByRole('button', { name: 'Save Minimum TMDB votes' }));
    expect(await screen.findByText('Saved. It applies from the next run.')).toBeOnTheScreen();
    expect(api.callsTo('PUT', PATH)[0]!.body).toEqual({ value: 12 });
    expect(client.getQueryData(queryKeys.adminSettings)).toEqual([{ ...setting, value: 12, overridden: true }]);
  });

  it("shows the server's field error", async () => {
    serveRoutes(fetchMock, [
      { method: 'PUT', path: PATH, status: 400, body: { message: 'Validation failed', fieldErrors: { value: 'Nope.' } } },
    ]);
    await renderWithClient(<SettingField setting={setting} />);
    await fireEvent.changeText(field(), '7');
    await fireEvent.press(screen.getByRole('button', { name: 'Save Minimum TMDB votes' }));
    expect(await screen.findByText('Nope.')).toBeOnTheScreen();
  });

  it('Cancel restores the stored value', async () => {
    await renderWithClient(<SettingField setting={setting} />);
    await fireEvent.changeText(field(), '9');
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel editing Minimum TMDB votes' }));
    expect(field().props.value).toBe('5');
  });

  it('an override can be reset to the default', async () => {
    const api = serveRoutes(fetchMock, [{ method: 'DELETE', path: PATH, body: setting }]);
    await renderWithClient(<SettingField setting={{ ...setting, value: 30, overridden: true }} />);
    expect(screen.getByText(/· overridden/)).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Reset Minimum TMDB votes to its default' }));
    expect(api.callsTo('DELETE', PATH)).toHaveLength(1);
  });
});

describe('SettingField — booleans', () => {
  const setting = makeBoolSetting();
  const PATH = '/admin/settings/catalog.quality.require-poster';

  it('saves at once when toggled', async () => {
    const api = serveRoutes(fetchMock, [{ method: 'PUT', path: PATH, body: { ...setting, value: false } }]);
    await renderWithClient(<SettingField setting={setting} />);
    const toggle = screen.getByRole('switch', { name: 'Require a poster' });
    expect(toggle).toBeChecked();
    expect(screen.getByText('Default on')).toBeOnTheScreen();
    await fireEvent(toggle, 'valueChange', false);
    expect(api.callsTo('PUT', PATH)[0]!.body).toEqual({ value: false });
  });

  it('offers Use default when overridden and shows failures', async () => {
    serveRoutes(fetchMock, [{ method: 'DELETE', path: PATH, status: 500, body: { message: 'boom' } }]);
    await renderWithClient(<SettingField setting={{ ...setting, value: false, overridden: true }} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Use default' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The server had a problem. Try again in a moment.');
  });
});

describe('JobCard', () => {
  it.each([
    [makeJob({ running: true }), 'Running'],
    [makeJob({ paused: true }), 'Paused'],
    [makeJob({ lastRunStale: true }), 'Last run unknown'],
    [makeJob(), 'Scheduled'],
    [makeJob({ scheduled: false }), 'Not scheduled here'],
  ])('status %#', (job, label) => {
    expect(jobStatus(job).label).toBe(label);
  });

  it('shows schedule, next run, last run and its numbers', async () => {
    await renderWithClient(<JobCard job={makeJob()} />);
    const card = screen.getByTestId('job-catalog-top-up');
    expect(within(card).getByText('Nightly catalog top-up')).toBeOnTheScreen();
    expect(within(card).getByText(/0 0 4 \* \* \*/)).toBeOnTheScreen();
    expect(within(card).getByText(/Oct 24, 04:00 UTC/)).toBeOnTheScreen();
    expect(screen.getByTestId('job-outcome-catalog-top-up')).toHaveTextContent('Completed');
    expect(within(card).getByText(/took 3m 05s · Scheduled/)).toBeOnTheScreen();
    expect(within(card).getByText('fetched 120 · failed 0')).toBeOnTheScreen();
  });

  it('marks a stale run as unknown and shows a failure', async () => {
    await renderWithClient(
      <JobCard
        job={makeJob({
          lastRunStale: true,
          lastRun: makeRun({ outcome: 'RUNNING', finishedAt: null, summary: { error: 'Boom: db' } }),
        })}
      />,
    );
    expect(screen.getByTestId('job-outcome-catalog-top-up')).toHaveTextContent('Unknown (stopped mid-run)');
    expect(screen.getByText('Boom: db')).toBeOnTheScreen();
  });

  it('jobs without controls have no buttons', async () => {
    await renderWithClient(<JobCard job={makeJob({ name: 'closeness', label: 'Friend closeness', controllable: false, lastRun: null })} />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByTestId('job-outcome-closeness')).toHaveTextContent('Never run');
  });

  it('Run now posts and confirms with the run id', async () => {
    const api = serveRoutes(fetchMock, [
      { method: 'POST', path: '/admin/jobs/catalog-top-up/run', status: 202, body: { jobName: 'catalog-top-up', runId: 42, triggeredBy: 'admin:u1' } },
      { path: '/admin/jobs', body: [makeJob()] },
      { path: /\/admin\/audit-log/, body: { items: [], nextCursor: null } },
    ]);
    await renderWithClient(<JobCard job={makeJob()} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Run Nightly catalog top-up now' }));
    expect(await screen.findByText(/Started \(run #42\)/)).toBeOnTheScreen();
    expect(api.callsTo('POST', '/admin/jobs/catalog-top-up/run')).toHaveLength(1);
  });

  it('Run now explains a 409', async () => {
    serveRoutes(fetchMock, [
      { method: 'POST', path: '/admin/jobs/catalog-top-up/run', status: 409, body: { message: 'ALREADY_RUNNING: …' } },
    ]);
    await renderWithClient(<JobCard job={makeJob()} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Run Nightly catalog top-up now' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Another catalog job is running. Try again when it has finished.');
  });

  it('Run now is off while the job runs', async () => {
    await renderWithClient(<JobCard job={makeJob({ running: true })} />);
    expect(screen.getByRole('button', { name: 'Run Nightly catalog top-up now' })).toBeDisabled();
  });

  it('Pause and Resume call the right endpoint and update the jobs cache', async () => {
    const paused = makeJob({ paused: true });
    const api = serveRoutes(fetchMock, [
      { method: 'POST', path: '/admin/jobs/catalog-top-up/pause', body: paused },
      { path: /.*/, body: [] },
    ]);
    const { client, rerender } = await renderWithClient(<JobCard job={makeJob()} />);
    client.setQueryData(queryKeys.adminJobs, [makeJob()]);
    await fireEvent.press(screen.getByRole('button', { name: 'Pause Nightly catalog top-up' }));
    expect(api.callsTo('POST', '/admin/jobs/catalog-top-up/pause')).toHaveLength(1);
    expect(client.getQueryData(queryKeys.adminJobs)).toEqual([paused]);

    api.use({ method: 'POST', path: '/admin/jobs/catalog-top-up/resume', body: makeJob() });
    await rerender(<JobCard job={paused} />);
    expect(screen.getByText(/Paused: scheduled runs are skipped/)).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Resume Nightly catalog top-up' }));
    expect(api.callsTo('POST', '/admin/jobs/catalog-top-up/resume')).toHaveLength(1);
  });
});

describe('ReportCard', () => {
  const PATH = '/admin/reports/r1/resolve';

  it('previews the target and the reporter', async () => {
    await renderWithClient(<ReportCard report={makeReport({ openReportsOnTarget: 3 })} />);
    const target = screen.getByTestId('report-target-r1');
    expect(within(target).getByText('Alan Turing')).toBeOnTheScreen();
    expect(within(target).getByText('on Fight Club · scored 9/10')).toBeOnTheScreen();
    expect(within(target).getByText('The twist is that…')).toBeOnTheScreen();
    expect(screen.getByText('Spoilers')).toBeOnTheScreen();
    expect(screen.getByText('3 open reports on this')).toBeOnTheScreen();
    expect(screen.getByText('Reported by Grace Hopper: “ruins the ending”')).toBeOnTheScreen();
  });

  it('says when the target is gone and when its author is suspended', async () => {
    await renderWithClient(
      <ReportCard report={makeReport({ id: 'r2', targetType: 'COMMENT', target: { ...makeReport().target, exists: false } })} />,
    );
    expect(screen.getByText('This comment no longer exists.')).toBeOnTheScreen();
    await renderWithClient(
      <ReportCard report={makeReport({ id: 'r3', target: { ...makeReport().target, authorStatus: 'SUSPENDED' } })} />,
    );
    expect(screen.getByText(/· suspended/)).toBeOnTheScreen();
  });

  it('resolves with a trimmed note', async () => {
    const api = serveRoutes(fetchMock, [
      { method: 'POST', path: PATH, body: makeReport({ status: 'RESOLVED' }) },
      { path: /.*/, body: { items: [], nextCursor: null } },
    ]);
    await renderWithClient(<ReportCard report={makeReport()} />);
    await fireEvent.changeText(screen.getByLabelText('Note (optional)'), '  Removed it  ');
    await fireEvent.press(screen.getByRole('button', { name: /^Resolve report/ }));
    expect(api.callsTo('POST', PATH)[0]!.body).toEqual({ resolution: 'RESOLVED', note: 'Removed it' });
  });

  it('dismisses without a note', async () => {
    const api = serveRoutes(fetchMock, [
      { method: 'POST', path: PATH, body: makeReport({ status: 'DISMISSED' }) },
      { path: /.*/, body: { items: [], nextCursor: null } },
    ]);
    await renderWithClient(<ReportCard report={makeReport()} />);
    await fireEvent.press(screen.getByRole('button', { name: /^Dismiss report/ }));
    expect(api.callsTo('POST', PATH)[0]!.body).toEqual({ resolution: 'DISMISSED', note: null });
  });

  it('blocks an over-long note inline', async () => {
    const api = serveRoutes(fetchMock, []);
    await renderWithClient(<ReportCard report={makeReport()} />);
    await fireEvent.changeText(screen.getByLabelText('Note (optional)'), 'x'.repeat(501));
    expect(screen.getByText('Notes cannot exceed 500 characters')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: /^Resolve report/ })).toBeDisabled();
    expect(api.calls()).toHaveLength(0);
  });

  it('explains a 409 (already handled)', async () => {
    serveRoutes(fetchMock, [
      { method: 'POST', path: PATH, status: 409, body: { message: 'This report has already been handled.' } },
      { path: /.*/, body: { items: [], nextCursor: null } },
    ]);
    await renderWithClient(<ReportCard report={makeReport()} />);
    await fireEvent.press(screen.getByRole('button', { name: /^Dismiss report/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Someone already handled this report.');
  });

  it('a closed report shows its outcome instead of the controls', async () => {
    await renderWithClient(
      <ReportCard
        report={makeReport({
          status: 'RESOLVED',
          resolvedAt: new Date().toISOString(),
          resolvedBy: { id: 'u1', username: 'ada', displayName: null },
          resolutionNote: 'Removed',
        })}
      />,
    );
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByText('Resolved by @ada just now: Removed')).toBeOnTheScreen();
  });
});

describe('AuditRow', () => {
  it('shows action, target, admin and old → new', async () => {
    await render(<AuditRow entry={makeAuditEntry()} />);
    expect(screen.getByText('Setting changed')).toBeOnTheScreen();
    expect(screen.getByText('catalog.quality.min-votes')).toBeOnTheScreen();
    expect(screen.getByText('by Ada Lovelace')).toBeOnTheScreen();
    expect(screen.getByText('5 → 12')).toBeOnTheScreen();
  });

  it('handles a deleted admin and no values', async () => {
    await render(<AuditRow entry={makeAuditEntry({ admin: null, oldValue: null, newValue: null, target: null })} />);
    expect(screen.getByText('by Deleted admin')).toBeOnTheScreen();
    expect(screen.queryByText(/→/)).toBeNull();
  });
});
