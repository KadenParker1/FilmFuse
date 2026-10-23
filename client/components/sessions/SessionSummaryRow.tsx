import { Pressable, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { pluralize, timeAgo } from '../../lib/format';
import type { SessionSummaryDTO } from '../../types/api';
import { STATUS_LABELS } from './sessionCopy';

/** "Decided" / "Ended early" / "Expired" / "Voting open". */
export function summaryStatusLabel(session: SessionSummaryDTO): string {
  return session.endedEarly ? 'Ended early' : STATUS_LABELS[session.status];
}

/** A past or ongoing session in a history list: the picked movie's poster and title (or status), size and when. */
export function SessionSummaryRow({
  session,
  onPress,
  showGroup = false,
}: {
  session: SessionSummaryDTO;
  onPress: () => void;
  /** Mixed lists (the Groups tab history) name the group, or "Tonight" for ad-hoc sessions. */
  showGroup?: boolean;
}) {
  const title = session.pickedMovie?.title ?? summaryStatusLabel(session);
  const meta = [showGroup ? (session.groupName ?? 'Tonight') : null, pluralize(session.memberCount, 'person', 'people'), timeAgo(session.createdAt)]
    .filter(Boolean)
    .join(' · ');
  const poster = session.pickedMovie?.posterUrl;
  return (
    <Pressable
      testID={`session-row-${session.id}`}
      accessibilityRole="button"
      accessibilityLabel={`${title}, ${meta}`}
      onPress={onPress}
      className="min-h-14 flex-row items-center gap-3 py-2 active:opacity-70"
    >
      {poster ? (
        <View className="h-14 w-10 overflow-hidden rounded-md bg-raised">
          <Image
            testID={`session-poster-${session.id}`}
            source={{ uri: poster }}
            style={{ width: '100%', height: '100%' }}
            contentFit="cover"
          />
        </View>
      ) : (
        <View className="w-10 items-center">
          <View
            className={`h-2.5 w-2.5 rounded-full ${
              session.status === 'OPEN' ? 'bg-success' : session.status === 'DECIDED' ? 'bg-accent' : 'bg-border'
            }`}
          />
        </View>
      )}
      <View className="flex-1">
        <Text className="font-body-semibold text-base text-text" numberOfLines={1}>
          {title}
        </Text>
        <Text className="font-body text-xs text-muted">{meta}</Text>
      </View>
    </Pressable>
  );
}
