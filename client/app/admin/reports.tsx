import { useState } from 'react';
import { View } from 'react-native';
import { useAdminReports } from '../../api/admin';
import { AdminFrame } from '../../components/admin/AdminFrame';
import { ReportCard } from '../../components/admin/ReportCard';
import { Button, EmptyState, ErrorState, SegmentedControl, Skeleton } from '../../components/ui';
import type { ReportStatus } from '../../types/api';

const EMPTY: Record<ReportStatus, string> = {
  OPEN: 'Nothing to review. New reports show up here, oldest first.',
  RESOLVED: 'No resolved reports yet.',
  DISMISSED: 'No dismissed reports yet.',
};

/** Admin → Reports: the moderation queue (open first), and what was resolved or dismissed. */
export default function AdminReportsScreen() {
  const [status, setStatus] = useState<ReportStatus>('OPEN');
  const reports = useAdminReports(status);
  const items = reports.data?.pages.flatMap((page) => page.items) ?? [];

  let content;
  if (reports.isPending) {
    content = (
      <View testID="reports-loading" className="gap-3">
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-40 w-full" />
      </View>
    );
  } else if (reports.isError) {
    content = <ErrorState title="Couldn't load reports" error={reports.error} onRetry={() => void reports.refetch()} />;
  } else if (items.length === 0) {
    content = <EmptyState icon="checkmark-done-outline" title="All clear" message={EMPTY[status]} />;
  } else {
    content = (
      <View className="gap-3">
        {items.map((report) => (
          <ReportCard key={report.id} report={report} />
        ))}
        {reports.hasNextPage ? (
          <Button
            title="Load more"
            variant="secondary"
            loading={reports.isFetchingNextPage}
            onPress={() => void reports.fetchNextPage()}
          />
        ) : null}
      </View>
    );
  }

  return (
    <AdminFrame current="reports" title="Reports">
      <SegmentedControl
        label="Report status"
        value={status}
        onChange={setStatus}
        segments={[
          { value: 'OPEN', label: 'Open' },
          { value: 'RESOLVED', label: 'Resolved' },
          { value: 'DISMISSED', label: 'Dismissed' },
        ]}
      />
      {content}
    </AdminFrame>
  );
}
