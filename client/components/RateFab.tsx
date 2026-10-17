import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../theme/useTheme';
import { useQuickRate } from './rating/QuickRateProvider';

type Props = { bottomOffset: number; rightOffset?: number };

/** The floating "+ Rate" button shown over every tab: movie search → the quick-rate sheet. */
export function RateFab({ bottomOffset, rightOffset = 20 }: Props) {
  const { colors } = useTheme();
  const { openRatePicker } = useQuickRate();

  return (
    <View pointerEvents="box-none" className="absolute" style={{ bottom: bottomOffset, right: rightOffset }}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Rate a movie"
        onPress={openRatePicker}
        className="flex-row items-center gap-1.5 rounded-full bg-gold px-5 py-3.5 shadow-lg active:opacity-80"
      >
        <Ionicons name="add" size={22} color={colors.onGold} />
        <Text className="font-body-semibold text-base text-on-gold">Rate</Text>
      </Pressable>
    </View>
  );
}
