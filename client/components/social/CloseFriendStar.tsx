import { Pressable } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../../theme/useTheme';

type Props = { name: string; closeFriend: boolean; onToggle: (next: boolean) => void; disabled?: boolean };

/** Gold star switch marking a close friend. */
export function CloseFriendStar({ name, closeFriend, onToggle, disabled }: Props) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={`Close friend: ${name}`}
      accessibilityState={{ checked: closeFriend, disabled: !!disabled }}
      disabled={disabled}
      hitSlop={8}
      onPress={() => onToggle(!closeFriend)}
      className="h-10 w-10 items-center justify-center rounded-full active:opacity-60"
    >
      <Ionicons
        name={closeFriend ? 'star' : 'star-outline'}
        size={22}
        color={closeFriend ? colors.accent : colors.muted}
      />
    </Pressable>
  );
}
