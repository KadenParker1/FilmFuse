import { useState } from 'react';
import { Text, View } from 'react-native';
import { useResolveReport } from '../../api/admin';
import { REPORT_NOTE_MAX, validateReportNote } from '../../lib/admin';
import { ApiError } from '../../lib/apiClient';
import { personName, timeAgo } from '../../lib/format';
import type { AdminReportDTO, AdminUserRefDTO, ReportReason, ReportResolution } from '../../types/api';
import { Button, Card, errorMessage, TextField } from '../ui';

const REASONS: Record<ReportReason, string> = {
  SPAM: 'Spam',
  HARASSMENT: 'Harassment',
  HATE: 'Hate',
  SEXUAL_CONTENT: 'Sexual content',
  SPOILERS: 'Spoilers',
  OTHER: 'Other',
};

const TARGETS: Record<AdminReportDTO['targetType'], string> = {
  RATING: 'Rating',
  COMMENT: 'Comment',
  USER: 'Profile',
};

const who = (user: AdminUserRefDTO | null) => (user ? personName(user) : 'Deleted account');

/** One report: what was reported (preview), by whom, and — while OPEN — resolve / dismiss with a note. */
export function ReportCard({ report }: { report: AdminReportDTO }) {
  const resolve = useResolveReport();
  const [note, setNote] = useState('');
  const noteError = validateReportNote(note);
  const { target } = report;
  const open = report.status === 'OPEN';
  const failure =
    resolve.error instanceof ApiError && resolve.error.status === 409
      ? 'Someone already handled this report.'
      : resolve.error instanceof ApiError && resolve.error.fieldErrors.note
        ? resolve.error.fieldErrors.note
        : resolve.error
          ? errorMessage(resolve.error)
          : null;

  const close = (resolution: ReportResolution) => {
    if (noteError) return;
    resolve.mutate({ id: report.id, resolution, note });
  };

  return (
    <Card testID={`report-${report.id}`} className="gap-3">
      <View className="flex-row flex-wrap items-center gap-2">
        <Text className="overflow-hidden rounded-full bg-danger-soft px-3 py-1 font-body-semibold text-xs text-danger">
          {REASONS[report.reason]}
        </Text>
        <Text className="font-body-semibold text-sm text-text">{TARGETS[report.targetType]}</Text>
        {report.openReportsOnTarget > 1 ? (
          <Text className="font-body-semibold text-sm text-accent2">{report.openReportsOnTarget} open reports on this</Text>
        ) : null}
        <Text className="ml-auto font-body text-xs text-muted">{timeAgo(report.createdAt)}</Text>
      </View>

      <View testID={`report-target-${report.id}`} className="gap-1 rounded-xl bg-raised p-3">
        {target.exists ? (
          <>
            <Text className="font-body-semibold text-sm text-text">
              {who(target.author)}
              {target.authorStatus === 'SUSPENDED' ? <Text className="text-danger"> · suspended</Text> : null}
            </Text>
            {target.movieTitle ? (
              <Text className="font-body text-sm text-muted">
                on {target.movieTitle}
                {target.score != null ? ` · scored ${target.score}/10` : ''}
              </Text>
            ) : null}
            {target.text ? <Text className="font-body text-sm text-text">{target.text}</Text> : null}
          </>
        ) : (
          <Text className="font-body text-sm text-muted">This {TARGETS[report.targetType].toLowerCase()} no longer exists.</Text>
        )}
      </View>

      <Text className="font-body text-sm text-muted">
        Reported by {who(report.reporter)}
        {report.details ? `: “${report.details}”` : ''}
      </Text>

      {open ? (
        <View className="gap-2">
          <TextField
            label="Note (optional)"
            value={note}
            onChangeText={setNote}
            multiline
            maxLength={REPORT_NOTE_MAX + 50}
            hint={`What you did, for the audit log. Up to ${REPORT_NOTE_MAX} characters.`}
            error={noteError}
          />
          <View className="flex-row flex-wrap gap-2">
            <Button
              title="Resolve"
              size="sm"
              loading={resolve.isPending && resolve.variables?.resolution === 'RESOLVED'}
              disabled={resolve.isPending || !!noteError}
              accessibilityLabel={`Resolve report: ${REASONS[report.reason]} on a ${TARGETS[report.targetType].toLowerCase()}`}
              onPress={() => close('RESOLVED')}
            />
            <Button
              title="Dismiss"
              size="sm"
              variant="secondary"
              loading={resolve.isPending && resolve.variables?.resolution === 'DISMISSED'}
              disabled={resolve.isPending || !!noteError}
              accessibilityLabel={`Dismiss report: ${REASONS[report.reason]} on a ${TARGETS[report.targetType].toLowerCase()}`}
              onPress={() => close('DISMISSED')}
            />
          </View>
          {failure ? (
            <Text accessibilityRole="alert" className="font-body text-sm text-danger">
              {failure}
            </Text>
          ) : null}
        </View>
      ) : (
        <Text className="font-body text-sm text-muted">
          {report.status === 'RESOLVED' ? 'Resolved' : 'Dismissed'} by {who(report.resolvedBy)} {timeAgo(report.resolvedAt)}
          {report.resolutionNote ? `: ${report.resolutionNote}` : ''}
        </Text>
      )}
    </Card>
  );
}
