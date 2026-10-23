import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { flattenPages } from '../../api/movies';
import { useLiveSessions, useSessions } from '../../api/sessions';
import { Body, Button, Card, ErrorState, Label, Skeleton } from '../ui';
import { LiveSessionCard } from './LiveSessionCard';
import { SessionSummaryRow } from './SessionSummaryRow';

/**
 * "Live now" on the Groups tab: every open session the caller is in (saved-group and ad-hoc), with
 * who's in, how many voted and a live countdown (refetched when one closes). Hidden entirely when there are none.
 */
export function LiveNowSection() {
  const router = useRouter();
  const live = useLiveSessions();
  if (live.isPending) {
    return (
      <View testID="live-loading">
        <Skeleton className="h-24 w-full" />
      </View>
    );
  }
  if (live.isError) {
    return (
      <ErrorState
        title="Couldn't load live sessions"
        error={live.error}
        onRetry={() => void live.refetch()}
        retrying={live.isFetching}
      />
    );
  }
  const items = live.data.items;
  if (!items.length) return null;
  return (
    <View testID="live-now" className="gap-3">
      <Label accessibilityRole="header">Live now · {items.length}</Label>
      {items.map((s) => (
        <LiveSessionCard
          key={s.id}
          session={s}
          onPress={() => router.push(`/sessions/${s.id}`)}
          onExpire={() => void live.refetch()}
        />
      ))}
    </View>
  );
}

/**
 * Past movie nights (decided, expired or ended early), newest first, paged. `groupId` narrows it to
 * one group (every status, so its open ones show too); otherwise it is the Groups tab's history.
 */
export function SessionHistorySection({ groupId, title }: { groupId?: string; title: string }) {
  const router = useRouter();
  const history = useSessions(groupId ?? null, groupId ? null : 'CLOSED');
  const items = flattenPages(history.data);

  let body;
  if (history.isPending) {
    body = (
      <View testID="history-loading">
        <Skeleton className="h-14 w-full" />
      </View>
    );
  } else if (history.isError && items.length === 0) {
    body = (
      <ErrorState
        title="Couldn't load movie nights"
        error={history.error}
        onRetry={() => void history.refetch()}
        retrying={history.isFetching}
      />
    );
  } else if (items.length === 0) {
    body = (
      <Body muted testID="no-sessions" className="py-3 text-center">
        No movie nights yet.
      </Body>
    );
  } else {
    body = items.map((s) => (
      <SessionSummaryRow key={s.id} session={s} showGroup={!groupId} onPress={() => router.push(`/sessions/${s.id}`)} />
    ));
  }

  return (
    <View testID="session-history" className="gap-2">
      <Label accessibilityRole="header">{title}</Label>
      <Card className="gap-1 py-2">{body}</Card>
      {history.hasNextPage ? (
        <Button
          size="sm"
          variant="ghost"
          title="Show more"
          loading={history.isFetchingNextPage}
          onPress={() => void history.fetchNextPage()}
        />
      ) : null}
    </View>
  );
}
