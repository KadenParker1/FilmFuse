import { Text, View } from 'react-native';
import { useTasteHistory } from '../../api/taste';
import { BackButton } from '../../components/BackButton';
import { formatScore, REASON_LABELS, dimensionValue } from '../../components/taste/tasteCopy';
import { Card, EmptyState, ErrorState, Heading, Screen, Skeleton } from '../../components/ui';
import { timeAgo } from '../../lib/format';
import type { TasteHistoryEntryDTO } from '../../types/api';

function HistoryEntry({ entry, last }: { entry: TasteHistoryEntryDTO; last: boolean }) {
  return (
    <View testID={`history-${entry.version}`} className="flex-row gap-3">
      <View className="items-center">
        <View className="mt-1.5 h-3 w-3 rounded-full bg-accent" />
        {!last ? <View className="w-0.5 flex-1 bg-border" /> : null}
      </View>
      <Card className="mb-4 flex-1 gap-2">
        <View className="flex-row items-center justify-between gap-2">
          <Text className="font-body-semibold text-base text-text">{REASON_LABELS[entry.reason]}</Text>
          <Text className="font-body text-xs text-muted">v{entry.version}</Text>
        </View>
        <Text className="font-body text-sm text-muted">{timeAgo(entry.createdAt)}</Text>
        {entry.topGenres.length > 0 ? (
          <View className="flex-row flex-wrap gap-2">
            {entry.topGenres.map((g) => {
              const v = dimensionValue(g);
              return (
                <View key={g.key} className="rounded-full bg-raised px-3 py-1">
                  <Text className="font-body-medium text-xs text-text">
                    {g.label}
                    {v != null ? ` ${formatScore(v)}` : ''}
                  </Text>
                </View>
              );
            })}
          </View>
        ) : (
          <Text testID={`history-${entry.version}-no-genres`} className="font-body text-sm text-muted">
            No standout genres in this version yet.
          </Text>
        )}
      </Card>
    </View>
  );
}

/** Taste editor → "How your taste changed": every vector version with why it was built. Read-only. */
export default function TasteHistoryScreen() {
  const history = useTasteHistory();

  let content;
  if (history.isPending) {
    content = (
      <View testID="history-loading" className="gap-4">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-24 w-full" />
        ))}
      </View>
    );
  } else if (history.isError) {
    content = (
      <ErrorState
        title="Couldn't load your taste history"
        error={history.error}
        onRetry={() => void history.refetch()}
        retrying={history.isFetching}
      />
    );
  } else if (history.data.length === 0) {
    content = (
      <EmptyState
        icon="time-outline"
        title="No changes yet"
        message="Each time your taste profile is rebuilt (after rating or editing your taste) it shows up here."
      />
    );
  } else {
    content = (
      <View testID="history-list">
        {history.data.map((e, i) => (
          <HistoryEntry key={e.version} entry={e} last={i === history.data.length - 1} />
        ))}
      </View>
    );
  }

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback="/taste" />
        <Heading size="md">How your taste changed</Heading>
      </View>
      {content}
    </Screen>
  );
}
