import { useColorScheme } from 'react-native';
import { palettes, type ColorScheme, type Palette } from './tokens';

/** Dark-first: anything other than an explicit "light" preference gets the dark palette. */
export function useScheme(): ColorScheme {
  return useColorScheme() === 'light' ? 'light' : 'dark';
}

export function useTheme(): { scheme: ColorScheme; colors: Palette } {
  const scheme = useScheme();
  return { scheme, colors: palettes[scheme] };
}
