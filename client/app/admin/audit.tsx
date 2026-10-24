import { useState } from 'react';
import { ScrollView, View } from 'react-native';
import { useAdminAudit } from '../../api/admin';
import { AdminFrame } from '../../components/admin/AdminFrame';
import { AuditRow } from '../../components/admin/AuditRow';
import { Button, Card, Chip, EmptyState, ErrorState, Skeleton } from '../../components/ui';
import { actionLabel, ADMIN_ACTIONS } from '../../lib/admin';
import type { AdminAction } from '../../types/api';

/** Admin → Audit log: every admin write, newest first, filterable by action. */
export default function AdminAuditScreen() {
  const [action, setAction] = useState<AdminAction | null>(null);
  const audit = useAdminAudit(action);
  const items = audit.data?.pages.flatMap((page) => page.items) ?? [];

  let content;
  if (audit.isPending) {
    content = <Skeleton testID="audit-loading" className="h-64 w-full" />;
  } else if (audit.isError) {
    content = <ErrorState title="Couldn't load the audit log" error={audit.error} onRetry={() => void audit.refetch()} />;
  } else if (items.length === 0) {
    content = <EmptyState icon="document-text-outline" title="Nothing logged" message="Admin changes are recorded here." />;
  } else {
    content = (
      <Card className="py-0">
        {items.map((entry) => (
          <AuditRow key={entry.id} entry={entry} />
        ))}
        {audit.hasNextPage ? (
          <Button
            title="Load more"
            variant="ghost"
            className="my-2"
            loading={audit.isFetchingNextPage}
            onPress={() => void audit.fetchNextPage()}
          />
        ) : null}
      </Card>
    );
  }

  return (
    <AdminFrame current="audit" title="Audit log">
      <ScrollView horizontal showsHorizontalScrollIndicator={false} className="flex-grow-0">
        <View accessibilityLabel="Filter by action" className="flex-row gap-2">
          <Chip label="All" selected={action === null} onPress={() => setAction(null)} />
          {ADMIN_ACTIONS.map((a) => (
            <Chip key={a} label={actionLabel(a)} selected={action === a} onPress={() => setAction(a)} />
          ))}
        </View>
      </ScrollView>
      {content}
    </AdminFrame>
  );
}
