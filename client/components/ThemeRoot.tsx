import type { ReactNode } from 'react';
import { View } from 'react-native';
import { vars } from 'nativewind';
import { paletteVars, type ColorScheme } from '../theme/tokens';
import { useScheme } from '../theme/useTheme';

const schemeVars: Record<ColorScheme, ReturnType<typeof vars>> = {
  dark: vars(paletteVars('dark')),
  light: vars(paletteVars('light')),
};

/**
 * Sets the Marquee colour CSS variables for everything inside it. Rendered once at the root, and
 * again inside anything that portals out of the tree on web (Modal), where DOM variables would
 * otherwise not be inherited.
 */
export function ThemeRoot({ children, className }: { children: ReactNode; className?: string }) {
  const scheme = useScheme();
  return (
    <View style={schemeVars[scheme]} className={className ?? 'flex-1 bg-bg'}>
      {children}
    </View>
  );
}
