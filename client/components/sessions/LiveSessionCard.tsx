import type { ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';
import { AvatarStack } from '../groups/AvatarStack';
import type { SessionSummaryDTO } from '../../types/api';
import { useCountdown } from './useCountdown';

/**
 * An open session on the Groups tab: group (or "Tonight"), who's in, how many voted, time left, and
 * a "Your vote" nudge when the caller hasn't voted. `children` holds extra actions (e.g. Join).
 * The countdown ticks live (quietly: it's part of the card's label, not a live region); `onExpire`
 * fires when it reaches zero so the list can refetch and drop the closed session.
 */
export function LiveSessionCard({
  session,
  onPress,
  onExpire,
  children,
}: {
  session: SessionSummaryDTO;
  onPress: () => void;
  onExpire?: () => void;
  children?: ReactNode;
}) {
  const name = session.groupName ?? 'Tonight';
  const voted = `${session.votedCount} of ${session.memberCount} voted`;
  const countdown = useCountdown(session.closesAt, onExpire);
  const needsVote = !session.votedByMe;
  return (
    <View className="gap-3 rounded-[18px] border border-border bg-surface p-4">
      <Pressable
        testID={`live-session-${session.id}`}
        accessibilityRole="button"
        accessibilityLabel={`${name}, ${voted}, ${countdown.spoken.toLowerCase()}${needsVote ? ', waiting on your vote' : ''}`}
        onPress={onPress}
        className="gap-2 active:opacity-80"
      >
        <View className="flex-row items-center gap-2">
          <View className="h-2.5 w-2.5 rounded-full bg-success" />
          <Text className="flex-1 font-display-bold text-lg tracking-tight text-text" numberOfLines={1}>
            {name}
          </Text>
          {needsVote ? (
            <View testID={`needs-vote-${session.id}`} className="rounded-full bg-accent2 px-2.5 py-0.5">
              <Text className="font-body-bold text-xs text-on-accent2">Your vote</Text>
            </View>
          ) : null}
        </View>
        <View className="flex-row items-center gap-3">
          <AvatarStack users={session.memberPreview} total={session.memberCount} size={28} />
          <Text testID={`live-session-meta-${session.id}`} className="flex-1 font-body text-xs text-muted">
            {voted} · {countdown.label}
          </Text>
        </View>
      </Pressable>
      {children}
    </View>
  );
}
