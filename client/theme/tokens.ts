/**
 * "Technicolor" design tokens - dark-first: saturated cobalt with a tangerine counterpoint on
 * crisp blue-black neutrals (light mode: the same accents, darkened, on near-white).
 *
 * This file is the single source of truth for colour. The palette is pushed into NativeWind as CSS
 * variables (see ThemeRoot), so Tailwind classes like `bg-surface` / `text-accent-ink` follow the
 * colour scheme, and `useTheme()` hands the raw hex values to the few props that cannot take a class
 * (tab bar tint, switch track, placeholder colour).
 *
 * Contrast (WCAG AA) is enforced by __tests__/tokens.test.ts: text and muted text reach 4.5:1 on
 * bg / surface / raised, each `on*` colour reaches 4.5:1 on its fill, and UI colours (accent,
 * danger, success, fuse members) reach 3:1 on the surfaces they sit on. That is why dark-mode
 * `onAccent` is a deep navy rather than white: white on #6B84FF is only 3.3:1.
 *
 * "Soft" colours are opaque (the accent blended over bg) because the CSS variables are RGB
 * triplets without alpha.
 */
export type ColorScheme = 'light' | 'dark';

export interface Palette {
  bg: string;
  surface: string;
  /** A step above surface: inputs, chips, secondary buttons. */
  raised: string;
  border: string;
  text: string;
  muted: string;
  /** Cobalt: primary buttons, active states, sliders, switches. */
  accent: string;
  /** Text/icon colour placed on an accent fill. */
  onAccent: string;
  /** Tinted background for an active nav item / score pill. */
  accentSoft: string;
  /** Accent-coloured text: links, scores, active nav labels. */
  accentInk: string;
  /** Tangerine: badges, "Pick", avatars - the social counterpoint. */
  accent2: string;
  onAccent2: string;
  success: string;
  successSoft: string;
  danger: string;
  onDanger: string;
  dangerSoft: string;
}

export const palettes: Record<ColorScheme, Palette> = {
  dark: {
    bg: '#0B0D14',
    surface: '#131622',
    raised: '#1B1F30',
    border: '#262B40',
    text: '#EEF0FB',
    muted: '#959BB8',
    accent: '#6B84FF',
    onAccent: '#0A0F2E',
    accentSoft: '#1A203A',
    accentInk: '#9AAAFF',
    accent2: '#FF9447',
    onAccent2: '#231003',
    success: '#3FDC9A',
    successSoft: '#122A27',
    danger: '#FF5D6C',
    onDanger: '#1A0306',
    dangerSoft: '#2D1820',
  },
  light: {
    bg: '#F8F9FE',
    surface: '#FFFFFF',
    raised: '#EEF0FB',
    border: '#E2E5F4',
    text: '#121528',
    muted: '#5B6180',
    accent: '#3451E0',
    onAccent: '#FFFFFF',
    accentSoft: '#E6EAFF',
    accentInk: '#2A43C4',
    accent2: '#E2661A',
    onAccent2: '#231003',
    success: '#0E7550',
    successSoft: '#E2F6EC',
    danger: '#C0243D',
    onDanger: '#FFFFFF',
    dangerSoft: '#FDE6E9',
  },
};

/**
 * One colour per member in the Fuse meter (and anywhere members are told apart), cycled when a
 * group is bigger. Light mode uses darker shades so each segment keeps 3:1 against the surface.
 */
export const fusePalettes: Record<ColorScheme, readonly string[]> = {
  dark: ['#6B84FF', '#FF9447', '#E45BD0', '#3FDC9A', '#FFD23F', '#4FD1E8'],
  light: ['#3451E0', '#C2560F', '#C43BB0', '#11845B', '#8C6A00', '#0A7F9B'],
};

/** Token name → CSS variable name used by tailwind.config.js (`onAccent2` → `--color-on-accent2`). */
export const cssVarName = (token: keyof Palette): string =>
  `--color-${token.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;

/** '#6B84FF' → '107 132 255', the form Tailwind's `<alpha-value>` colours need. */
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

/** WCAG relative luminance of a #RRGGBB colour. */
export function relativeLuminance(hex: string): number {
  const h = hex.replace('#', '');
  const channel = (i: number) => {
    const v = parseInt(h.slice(i, i + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
}

/** WCAG contrast ratio between two #RRGGBB colours (1-21). */
export function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** Bricolage Grotesque for display, Figtree for body (loaded in app/_layout.tsx). */
export const fonts = {
  displayMedium: 'BricolageGrotesque_500Medium',
  display: 'BricolageGrotesque_700Bold',
  displayBold: 'BricolageGrotesque_800ExtraBold',
  body: 'Figtree_400Regular',
  bodyMedium: 'Figtree_500Medium',
  bodySemibold: 'Figtree_600SemiBold',
  bodyBold: 'Figtree_700Bold',
} as const;

/** Wide screens swap the bottom tab bar for a nav rail on the right at this width. */
export const WIDE_BREAKPOINT = 1024;
