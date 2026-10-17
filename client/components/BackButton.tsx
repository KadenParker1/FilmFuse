import { Pressable } from 'react-native';
import { useRouter, type Href } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../theme/useTheme';

/** Round back button; after a deep link or web refresh (no history) it replaces with `fallback`. */
export function BackButton({ fallback = '/' }: { fallback?: Href }) {
  const router = useRouter();
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel="Back"
      hitSlop={8}
      onPress={() => (router.canGoBack() ? router.back() : router.replace(fallback))}
      className="h-10 w-10 items-center justify-center rounded-xl bg-raised active:opacity-70"
    >
      <Ionicons name="chevron-back" size={22} color={colors.text} />
    </Pressable>
  );
}
