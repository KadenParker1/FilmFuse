import { Pressable, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../theme/useTheme';
import { useQuickRate } from './rating/QuickRateProvider';

type Props = { bottomOffset: number; rightOffset?: number };

/**
 * Phones: the floating "+" Rate button above the tab bar (movie search → the quick-rate sheet). Wide
 * screens don't render it; the nav rail has "Rate a movie".
 */
export function RateFab({ bottomOffset, rightOffset = 16 }: Props) {
  const { colors } = useTheme();
  const { openRatePicker } = useQuickRate();

  return (
    <View pointerEvents="box-none" className="absolute" style={{ bottom: bottomOffset, right: rightOffset }}>
      <Pressable
        testID="rate-fab"
        accessibilityRole="button"
        accessibilityLabel="Rate a movie"
        onPress={openRatePicker}
        className="h-14 w-14 items-center justify-center rounded-2xl bg-accent active:opacity-80"
        style={{ shadowColor: colors.accent, shadowOpacity: 0.45, shadowRadius: 16, shadowOffset: { width: 0, height: 8 }, elevation: 6 }}
      >
        <Ionicons name="add" size={28} color={colors.onAccent} />
      </Pressable>
    </View>
  );
}
