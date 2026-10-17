import { ActivityIndicator, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../theme/useTheme';

export type ToastTone = 'busy' | 'success' | 'error';

type Props = {
  /** Nothing renders while null. */
  message: string | null;
  tone?: ToastTone;
  /** Distance from the bottom safe-area edge (clears the tab bar and "+ Rate"). */
  bottomOffset?: number;
};

/** A small pill floating near the bottom of the screen, announced to screen readers. */
export function Toast({ message, tone = 'success', bottomOffset = 96 }: Props) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  if (!message) return null;
  return (
    <View
      pointerEvents="none"
      className="absolute inset-x-0 items-center px-4"
      style={{ bottom: insets.bottom + bottomOffset }}
    >
      <View
        testID="toast"
        accessibilityRole="alert"
        accessibilityLiveRegion="polite"
        className="flex-row items-center gap-2 rounded-full border border-border bg-surface-raised px-4 py-2.5 shadow-lg"
      >
        {tone === 'busy' ? (
          <ActivityIndicator testID="toast-spinner" size="small" color={colors.gold} />
        ) : (
          <Ionicons
            name={tone === 'success' ? 'checkmark-circle' : 'alert-circle'}
            size={18}
            color={tone === 'success' ? colors.success : colors.velvet}
          />
        )}
        <Text className="font-body-medium text-sm text-text">{message}</Text>
      </View>
    </View>
  );
}
