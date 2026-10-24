import { Text, View } from 'react-native';
import { actionLabel, auditValue } from '../../lib/admin';
import { personName, timeAgo } from '../../lib/format';
import type { AdminAuditEntryDTO } from '../../types/api';

/** One audit-log entry: who did what to which target, old → new. */
export function AuditRow({ entry }: { entry: AdminAuditEntryDTO }) {
  const admin = entry.admin ? personName(entry.admin) : 'Deleted admin';
  const hasValues = entry.oldValue != null || entry.newValue != null;
  return (
    <View testID={`audit-${entry.id}`} className="gap-1 border-b border-border py-3">
      <View className="flex-row flex-wrap items-baseline gap-x-2">
        <Text className="font-body-semibold text-sm text-text">{actionLabel(entry.action)}</Text>
        {entry.target ? <Text className="font-body text-sm text-accent-ink">{entry.target}</Text> : null}
        <Text className="ml-auto font-body text-xs text-muted">{timeAgo(entry.createdAt)}</Text>
      </View>
      <Text className="font-body text-sm text-muted">by {admin}</Text>
      {hasValues ? (
        <Text className="font-body text-sm text-text" numberOfLines={4}>
          {auditValue(entry.oldValue)} → {auditValue(entry.newValue)}
        </Text>
      ) : null}
    </View>
  );
}
