import { Text, View } from 'react-native';
import { useRunJob, useSetJobPaused } from '../../api/admin';
import { formatUtc, runDuration, summaryHighlights, triggeredByLabel } from '../../lib/admin';
import { ApiError } from '../../lib/apiClient';
import { timeAgo } from '../../lib/format';
import type { AdminJobDTO, JobOutcome } from '../../types/api';
import { Button, Card, errorMessage } from '../ui';

type Status = { label: string; className: string };

/** The one-word state of a job; colour is backed by the word, never alone. */
export function jobStatus(job: AdminJobDTO): Status {
  if (job.running) return { label: 'Running', className: 'bg-accent text-on-accent' };
  if (job.paused) return { label: 'Paused', className: 'bg-accent2 text-on-accent2' };
  if (job.lastRunStale) return { label: 'Last run unknown', className: 'bg-danger-soft text-danger' };
  if (job.scheduled) return { label: 'Scheduled', className: 'bg-success-soft text-success' };
  return { label: 'Not scheduled here', className: 'bg-raised text-muted' };
}

const OUTCOME_TONE: Record<JobOutcome, string> = {
  RUNNING: 'text-accent-ink',
  COMPLETED: 'text-success',
  ABORTED: 'text-accent2',
  INTERRUPTED: 'text-accent2',
  FAILED: 'text-danger',
  SKIPPED: 'text-muted',
};

function outcomeLabel(job: AdminJobDTO): string {
  const last = job.lastRun;
  if (!last) return 'Never run';
  if (job.lastRunStale) return 'Unknown (stopped mid-run)';
  return last.outcome.charAt(0) + last.outcome.slice(1).toLowerCase();
}

/** A job: status, schedule, last run, and for the catalog jobs Run now and Pause / Resume. */
export function JobCard({ job }: { job: AdminJobDTO }) {
  const run = useRunJob();
  const pause = useSetJobPaused();
  const status = jobStatus(job);
  const last = job.lastRun;
  const busy = run.isPending || pause.isPending;
  const runError =
    run.error instanceof ApiError && run.error.status === 409
      ? 'Another catalog job is running. Try again when it has finished.'
      : run.error
        ? errorMessage(run.error)
        : null;
  const duration = last ? runDuration(last.startedAt, last.finishedAt) : null;
  const highlights = last ? summaryHighlights(last.summary) : [];
  const error = last && typeof last.summary.error === 'string' ? last.summary.error : null;

  return (
    <Card testID={`job-${job.name}`} className="gap-3">
      <View className="flex-row flex-wrap items-center justify-between gap-2">
        <View className="flex-1 gap-0.5">
          <Text accessibilityRole="header" className="font-body-bold text-base text-text">
            {job.label}
          </Text>
          <Text className="font-body text-xs text-muted">{job.name}</Text>
        </View>
        <Text
          testID={`job-status-${job.name}`}
          className={`overflow-hidden rounded-full px-3 py-1 font-body-semibold text-xs ${status.className}`}
        >
          {status.label}
        </Text>
      </View>

      <View className="gap-1">
        <Text className="font-body text-sm text-muted">
          Schedule: <Text className="text-text">{job.schedule ?? 'manual only'}</Text>
          {job.nextRunAt ? (
            <Text>
              {' '}
              · next <Text className="text-text">{formatUtc(job.nextRunAt)}</Text>
            </Text>
          ) : null}
        </Text>
        <Text className="font-body text-sm text-muted">
          Last run:{' '}
          <Text testID={`job-outcome-${job.name}`} className={last && !job.lastRunStale ? OUTCOME_TONE[last.outcome] : 'text-danger'}>
            {outcomeLabel(job)}
          </Text>
          {last ? (
            <Text>
              {' '}
              · {timeAgo(last.startedAt)}
              {duration ? ` · took ${duration}` : ''} · {triggeredByLabel(last.triggeredBy)}
            </Text>
          ) : null}
        </Text>
        {highlights.length > 0 ? <Text className="font-body text-sm text-muted">{highlights.join(' · ')}</Text> : null}
        {error ? (
          <Text className="font-body text-sm text-danger" numberOfLines={3}>
            {error}
          </Text>
        ) : null}
      </View>

      {job.controllable ? (
        <View className="flex-row flex-wrap gap-2">
          <Button
            title="Run now"
            size="sm"
            loading={run.isPending}
            disabled={busy || job.running}
            accessibilityLabel={`Run ${job.label} now`}
            onPress={() => run.mutate(job.name)}
          />
          <Button
            title={job.paused ? 'Resume' : 'Pause'}
            size="sm"
            variant="secondary"
            loading={pause.isPending}
            disabled={busy}
            accessibilityLabel={`${job.paused ? 'Resume' : 'Pause'} ${job.label}`}
            onPress={() => pause.mutate({ name: job.name, paused: !job.paused })}
          />
        </View>
      ) : null}
      {run.isSuccess ? (
        <Text accessibilityLiveRegion="polite" className="font-body text-sm text-success">
          Started{run.data.runId != null ? ` (run #${run.data.runId})` : ''}. This page updates while it runs.
        </Text>
      ) : null}
      {runError ? (
        <Text accessibilityRole="alert" className="font-body text-sm text-danger">
          {runError}
        </Text>
      ) : null}
      {pause.isError ? (
        <Text accessibilityRole="alert" className="font-body text-sm text-danger">
          {errorMessage(pause.error)}
        </Text>
      ) : null}
      {job.paused ? (
        <Text className="font-body text-xs text-muted">Paused: scheduled runs are skipped. Run now still works.</Text>
      ) : null}
    </Card>
  );
}
