/**
 * "Marquee" design tokens — dark-first cinema lobby: velvet, gold bulbs.
 *
 * This file is the single source of truth for colour. The palette is pushed into NativeWind as CSS
 * variables (see ThemeRoot), so Tailwind classes like `bg-bg` / `text-gold` follow the colour
 * scheme, and `useTheme()` hands the raw hex values to the few props that cannot take a class
 * (tab bar tint, slider track, placeholder colour).
 *
 * Light-mode accents are darkened so text and icons in them reach ~4.5:1 on the light background.
 */
export type ColorScheme = 'light' | 'dark';

export interface Palette {
  bg: string;
  surface: string;
  surfaceRaised: string;
  border: string;
  text: string;
  textMuted: string;
  gold: string;
  /** Text/icon colour placed on a gold fill. */
  onGold: string;
  velvet: string;
  /** Text/icon colour placed on a velvet fill. */
  onVelvet: string;
  success: string;
}

export const palettes: Record<ColorScheme, Palette> = {
  dark: {
    bg: '#0E0B14',
    surface: '#1A1523',
    surfaceRaised: '#251E31',
    border: '#3A3047',
    text: '#F4EFE6',
    textMuted: '#A39BB0',
    gold: '#F5B942',
    onGold: '#1B1622',
    velvet: '#C7354A',
    onVelvet: '#FFFFFF',
    success: '#4CC38A',
  },
  light: {
    bg: '#FBF7F0',
    surface: '#FFFFFF',
    surfaceRaised: '#F3ECE0',
    border: '#E0D5C4',
    text: '#1B1622',
    textMuted: '#5E5670',
    gold: '#8A5A00',
    onGold: '#FFFFFF',
    velvet: '#A3253A',
    onVelvet: '#FFFFFF',
    success: '#1E7A4E',
  },
};

/** Token name → CSS variable name used by tailwind.config.js. */
export const cssVarName = (token: keyof Palette): string =>
  `--color-${token.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;

/** '#F5B942' → '245 185 66', the form Tailwind's `<alpha-value>` colours need. */
export function hexToRgbTriplet(hex: string): string {
  const h = hex.replace('#', '');
  const n = parseInt(h, 16);
  return `${(n >> 16) & 255} ${(n >> 8) & 255} ${n & 255}`;
}

/** The CSS variables for one scheme, ready for NativeWind's `vars()`. */
export function paletteVars(scheme: ColorScheme): Record<string, string> {
  const p = palettes[scheme];
  return Object.fromEntries(
    (Object.keys(p) as (keyof Palette)[]).map((k) => [cssVarName(k), hexToRgbTriplet(p[k])]),
  );
}

export const fonts = {
  display: 'Fraunces_600SemiBold',
  displayBold: 'Fraunces_700Bold',
  body: 'Inter_400Regular',
  bodyMedium: 'Inter_500Medium',
  bodySemibold: 'Inter_600SemiBold',
} as const;

/** Wide web screens swap the bottom tab bar for a left side rail at this width. */
export const WIDE_BREAKPOINT = 1024;
