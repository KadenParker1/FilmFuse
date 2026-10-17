import { ActivityIndicator, View } from 'react-native';
import { useTheme } from '../theme/useTheme';

export function FullScreenLoader({ label = 'Loading' }: { label?: string }) {
  const { colors } = useTheme();
  return (
    <View className="flex-1 items-center justify-center bg-bg" accessibilityLabel={label}>
      <ActivityIndicator size="large" color={colors.accent} />
    </View>
  );
}
