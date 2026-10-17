import { Pressable, Text, View } from 'react-native';
import { pluralize, timeAgo } from '../../lib/format';
import type { SessionSummaryDTO } from '../../types/api';
import { STATUS_LABELS } from './sessionCopy';

/** A past or ongoing session in a history list: the pick (or status), size and when. */
export function SessionSummaryRow({ session, onPress }: { session: SessionSummaryDTO; onPress: () => void }) {
  const title = session.pickedMovie?.title ?? STATUS_LABELS[session.status];
  const meta = [pluralize(session.memberCount, 'person', 'people'), timeAgo(session.createdAt)]
    .filter(Boolean)
    .join(' · ');
  return (
    <Pressable
      testID={`session-row-${session.id}`}
      accessibilityRole="button"
      accessibilityLabel={`${title}, ${meta}`}
      onPress={onPress}
      className="min-h-14 flex-row items-center gap-3 py-2 active:opacity-70"
    >
      <View
        className={`h-2.5 w-2.5 rounded-full ${
          session.status === 'OPEN' ? 'bg-success' : session.status === 'DECIDED' ? 'bg-accent' : 'bg-border'
        }`}
      />
      <View className="flex-1">
        <Text className="font-body-semibold text-base text-text" numberOfLines={1}>
          {title}
        </Text>
        <Text className="font-body text-xs text-muted">{meta}</Text>
      </View>
    </Pressable>
  );
}
