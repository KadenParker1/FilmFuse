import { Stack } from 'expo-router';
import { stackScreenOptions } from '../../theme/navigation';
import { useTheme } from '../../theme/useTheme';

export default function AuthLayout() {
  const { colors } = useTheme();
  return <Stack screenOptions={stackScreenOptions(colors.bg)} />;
}
