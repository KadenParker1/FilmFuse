import { ScrollView, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { personName } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { SessionMemberDTO } from '../../types/api';
import { Avatar, Body } from '../ui';

/** "Grace has" / "Grace and Alan have" / "Grace, Alan and Joan have". */
function joinNames(names: string[]): string {
  if (names.length <= 1) return `${names[0] ?? ''} has`;
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]} have`;
}

/**
 * Everyone in the session the viewer can see, with a tick once they've voted, plus a hint about
 * missing taste profiles. The "x of y voted" line uses the server's counts, which include members
 * left out of `members` because they are blocked either way with the viewer.
 */
export function SessionMembersRow({
  members,
  memberCount = members.length,
  votedCount,
}: {
  members: SessionMemberDTO[];
  memberCount?: number;
  votedCount?: number;
}) {
  const { colors } = useTheme();
  const noVector = members.filter((m) => !m.hasVector).map((m) => personName(m.user));
  const voted = votedCount ?? members.filter((m) => m.voted).length;
  return (
    <View className="gap-2">
      <Text className="font-body-semibold text-sm text-text-muted">
        {voted} of {memberCount} voted
      </Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerClassName="gap-3">
        {members.map((m) => {
          const name = personName(m.user);
          return (
            <View
              key={m.user.id}
              testID={`session-member-${m.user.id}`}
              accessible
              accessibilityLabel={`${name}, ${m.voted ? 'has voted' : 'hasn’t voted yet'}${
                m.hasVector ? '' : ', no taste profile yet'
              }`}
              className="w-16 items-center gap-1"
            >
              <View>
                <Avatar
                  uri={m.user.profileImageUrl}
                  displayName={m.user.displayName}
                  username={m.user.username}
                  size={44}
                  className={m.voted ? '' : 'opacity-60'}
                />
                {m.voted ? (
                  <View
                    testID={`voted-${m.user.id}`}
                    className="absolute -bottom-1 -right-1 rounded-full bg-success"
                  >
                    <Ionicons name="checkmark-circle" size={18} color={colors.onGold} />
                  </View>
                ) : null}
              </View>
              <Text className="font-body text-xs text-text" numberOfLines={1}>
                {name}
              </Text>
            </View>
          );
        })}
      </ScrollView>
      {noVector.length ? (
        <Body muted testID="no-vector-hint" className="text-xs">
          {joinNames(noVector)} no taste profile yet, so their fit leans on what’s popular.
        </Body>
      ) : null}
    </View>
  );
}
