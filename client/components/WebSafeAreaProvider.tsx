import type { ReactNode } from 'react';
import { Platform } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

/**
 * Real safe-area insets on web.
 *
 * expo-router wraps every app in its own SafeAreaProvider, and on web that provider hands out
 * fixed ZERO insets (it skips measuring to keep streamed SSR hydration stable). Every
 * `useSafeAreaInsets()` - Screen, the tab bar, the Rate button, the nav rail, sheets, toasts -
 * would read 0 under the iPhone notch and home indicator. This nested provider measures
 * `env(safe-area-inset-*)` (react-native-safe-area-context's web implementation, which needs
 * `viewport-fit=cover` from app/+html.tsx) and overrides the zeros. It starts from the parent's
 * zeros, so the static HTML and the first client render still match.
 *
 * Native: expo-router's provider already measures the real insets, so this renders children as is.
 */
export function WebSafeAreaProvider({ children, os = Platform.OS }: { children: ReactNode; os?: string }) {
  if (os !== 'web') return <>{children}</>;
  return <SafeAreaProvider>{children}</SafeAreaProvider>;
}
