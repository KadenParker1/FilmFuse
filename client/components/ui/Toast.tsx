import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../theme/useTheme';

export type ToastTone = 'busy' | 'success' | 'error' | 'info';

export type ToastAction = {
  /** The visible button text, e.g. "Undo". */
  label: string;
  onPress: () => void;
  /** What a screen reader says for the button, e.g. "Undo removing Heat". Defaults to `label`. */
  accessibilityLabel?: string;
};

type Props = {
  /** Nothing renders while null. */
  message: string | null;
  tone?: ToastTone;
  /** Distance from the bottom safe-area edge (clears the tab bar and "+ Rate"). */
  bottomOffset?: number;
  /** An optional button inside the pill (e.g. Undo). */
  action?: ToastAction | null;
  testID?: string;
};

const ICONS = { success: 'checkmark-circle', error: 'alert-circle', info: 'information-circle' } as const;

/** A small pill floating near the bottom of the screen, announced to screen readers. */
export function Toast({ message, tone = 'success', bottomOffset = 96, action, testID = 'toast' }: Props) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  if (!message) return null;
  const iconColor = tone === 'success' ? colors.success : tone === 'error' ? colors.danger : colors.accent;
  return (
    <View
      // Touches pass through the empty area around the pill; only the action button takes them.
      pointerEvents={action ? 'box-none' : 'none'}
      className="absolute inset-x-0 items-center px-4"
      style={{ bottom: insets.bottom + bottomOffset }}
    >
      <View
        testID={testID}
        accessibilityRole="alert"
        accessibilityLiveRegion="polite"
        className="flex-row items-center gap-2 rounded-full bg-raised px-4 py-2.5 shadow-lg"
      >
        {tone === 'busy' ? (
          <ActivityIndicator testID="toast-spinner" size="small" color={colors.accent} />
        ) : (
          <Ionicons name={ICONS[tone]} size={18} color={iconColor} />
        )}
        <Text className="font-body-medium text-sm text-text">{message}</Text>
        {action ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={action.accessibilityLabel ?? action.label}
            hitSlop={8}
            onPress={action.onPress}
            className="-my-1 ml-1 min-h-9 justify-center rounded-full px-3 active:opacity-70"
          >
            <Text className="font-body-bold text-sm text-accent-ink">{action.label}</Text>
          </Pressable>
        ) : null}
      </View>
    </View>
  );
}
