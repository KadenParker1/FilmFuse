import { useColorScheme } from 'react-native';
import { resolveScheme } from '../lib/appearancePreference';
import { useAppearance } from './AppearanceProvider';
import { fusePalettes, palettes, type ColorScheme, type Palette } from './tokens';

/**
 * Dark-first: the scheme is dark unless Settings → Appearance says Light, or says System and the
 * system is light.
 */
export function useScheme(): ColorScheme {
  const { preference } = useAppearance();
  const system = useColorScheme();
  return resolveScheme(preference, system);
}

export function useTheme(): { scheme: ColorScheme; colors: Palette; fuse: readonly string[] } {
  const scheme = useScheme();
  return { scheme, colors: palettes[scheme], fuse: fusePalettes[scheme] };
}

/** The status bar's content style for a scheme: light text on the dark palette, dark on light. */
export function statusBarStyle(scheme: ColorScheme): 'light' | 'dark' {
  return scheme === 'dark' ? 'light' : 'dark';
}
