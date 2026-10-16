import { Text, View } from 'react-native';
import { personName } from '../../lib/format';
import { Avatar } from '../ui';
import type { RowUser } from '../social/UserRow';

type Props = {
  users: RowUser[];
  /** Everyone in the group; anyone beyond the shown avatars becomes "+N". */
  total?: number;
  max?: number;
  size?: number;
};

/** Overlapping profile photos with a "+N" overflow pill. Read out as one list of names. */
export function AvatarStack({ users, total, max = 5, size = 32 }: Props) {
  const shown = users.slice(0, max);
  const extra = Math.max(0, (total ?? users.length) - shown.length);
  const names = shown.map(personName).join(', ');
  return (
    <View
      testID="avatar-stack"
      accessible
      accessibilityLabel={extra > 0 ? `${names} and ${extra} more` : names}
      className="flex-row items-center"
    >
      {shown.map((u, i) => (
        <View key={u.id} style={{ marginLeft: i === 0 ? 0 : -size / 3 }}>
          <Avatar uri={u.profileImageUrl} displayName={u.displayName} username={u.username} size={size} />
        </View>
      ))}
      {extra > 0 ? (
        <View
          testID="avatar-stack-extra"
          className="items-center justify-center rounded-full border-2 border-gold bg-surface-raised"
          style={{ width: size, height: size, marginLeft: shown.length ? -size / 3 : 0 }}
        >
          <Text className="font-body-semibold text-xs text-text">+{extra}</Text>
        </View>
      ) : null}
    </View>
  );
}
