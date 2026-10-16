import type { ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';
import { pluralize, timeAgo } from '../../lib/format';
import type { GroupSummaryDTO } from '../../types/api';
import { AvatarStack } from './AvatarStack';
import { groupColor, groupEmoji } from './groupStyle';

type Props = {
  group: GroupSummaryDTO;
  onPress?: () => void;
  /** Trailing actions (e.g. Accept / Decline on an invite). */
  children?: ReactNode;
};

/** A group as a card: emoji tile in its colour, name, member photos and the last movie night. */
export function GroupCard({ group, onPress, children }: Props) {
  const color = groupColor(group.color);
  const last = timeAgo(group.lastSessionAt);
  const subtitle =
    group.memberStatus === 'INVITED'
      ? 'You’re invited'
      : `${pluralize(group.memberCount, 'member')} · ${last ? `Last movie night ${last}` : 'No movie nights yet'}`;
  return (
    <View
      testID={`group-card-${group.id}`}
      className="overflow-hidden rounded-2xl border border-border bg-surface"
      style={{ borderLeftWidth: 4, borderLeftColor: color }}
    >
      <Pressable
        accessibilityRole={onPress ? 'button' : undefined}
        accessibilityLabel={`${group.name}, ${subtitle}`}
        disabled={!onPress}
        onPress={onPress}
        className="flex-row items-center gap-3 p-4 active:opacity-80"
      >
        <View
          className="h-12 w-12 items-center justify-center rounded-xl"
          style={{ backgroundColor: `${color}33` }}
        >
          <Text className="text-2xl">{groupEmoji(group.emoji)}</Text>
        </View>
        <View className="flex-1 gap-1">
          <Text className="font-display text-lg text-text" numberOfLines={1}>
            {group.name}
          </Text>
          <Text className="font-body text-xs text-text-muted" numberOfLines={1}>
            {subtitle}
          </Text>
        </View>
        {group.memberPreview.length ? (
          <AvatarStack users={group.memberPreview} total={group.memberCount} max={4} size={28} />
        ) : null}
      </Pressable>
      {children ? <View className="flex-row gap-2 px-4 pb-4">{children}</View> : null}
    </View>
  );
}
