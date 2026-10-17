import fs from 'fs';
import path from 'path';
import {
  contrastRatio,
  cssVarName,
  fonts,
  fusePalettes,
  hexToRgbTriplet,
  palettes,
  paletteVars,
  type ColorScheme,
  type Palette,
} from '../theme/tokens';

const SCHEMES: ColorScheme[] = ['dark', 'light'];
type Pair = [fg: keyof Palette, bg: keyof Palette];

/** Body text (any size): WCAG AA 4.5:1. */
const BODY_PAIRS: Pair[] = [
  ['text', 'bg'],
  ['text', 'surface'],
  ['text', 'raised'],
  ['muted', 'bg'],
  ['muted', 'surface'],
  ['muted', 'raised'],
  ['onAccent', 'accent'],
  ['onAccent2', 'accent2'],
  ['onDanger', 'danger'],
  ['accentInk', 'bg'],
  ['accentInk', 'surface'],
  ['accentInk', 'accentSoft'],
  ['danger', 'bg'],
  ['danger', 'surface'],
  ['danger', 'dangerSoft'],
  ['success', 'bg'],
  ['success', 'surface'],
  ['success', 'successSoft'],
  // Selected chips / segments: bg-coloured text on a text-coloured fill.
  ['bg', 'text'],
];

/** Large text and UI components (icons, switch tracks, focus rings, meter segments): 3:1. */
const UI_PAIRS: Pair[] = [
  ['accent', 'bg'],
  ['accent', 'surface'],
  ['accent', 'raised'],
  ['accent2', 'surface'],
  ['danger', 'raised'],
  ['muted', 'surface'], // switch "off" track
];

describe.each(SCHEMES)('Technicolor %s palette contrast', (scheme) => {
  const p = palettes[scheme];

  it.each(BODY_PAIRS)('%s on %s reaches 4.5:1', (fg, bg) => {
    expect(contrastRatio(p[fg], p[bg])).toBeGreaterThanOrEqual(4.5);
  });

  it.each(UI_PAIRS)('%s on %s reaches 3:1', (fg, bg) => {
    expect(contrastRatio(p[fg], p[bg])).toBeGreaterThanOrEqual(3);
  });

  it('every Fuse member colour reaches 3:1 on the surfaces the meter sits on', () => {
    for (const c of fusePalettes[scheme]) {
      expect(contrastRatio(c, p.surface)).toBeGreaterThanOrEqual(3);
      expect(contrastRatio(c, p.raised)).toBeGreaterThanOrEqual(3);
    }
  });

  it('every token is a #RRGGBB hex and becomes an RGB triplet CSS variable', () => {
    for (const [k, v] of Object.entries(p)) {
      expect(v).toMatch(/^#[0-9A-F]{6}$/);
      expect(paletteVars(scheme)[cssVarName(k as keyof Palette)]).toBe(hexToRgbTriplet(v));
    }
  });
});

describe('tokens', () => {
  it('computes WCAG contrast ratios', () => {
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 5);
    expect(contrastRatio('#FFFFFF', '#FFFFFF')).toBeCloseTo(1, 5);
    // Order doesn't matter.
    expect(contrastRatio('#6B84FF', '#0B0D14')).toBeCloseTo(contrastRatio('#0B0D14', '#6B84FF'), 10);
  });

  it('keeps the mockup Technicolor values for the core colours', () => {
    expect(palettes.dark).toMatchObject({ bg: '#0B0D14', surface: '#131622', accent: '#6B84FF', accent2: '#FF9447' });
    expect(palettes.light).toMatchObject({ bg: '#F8F9FE', surface: '#FFFFFF', accent: '#3451E0', accent2: '#E2661A' });
    expect(fusePalettes.dark.slice(0, 4)).toEqual(['#6B84FF', '#FF9447', '#E45BD0', '#3FDC9A']);
  });

  it('names CSS variables the way tailwind.config.js expects', () => {
    expect(cssVarName('onAccent2')).toBe('--color-on-accent2');
    expect(cssVarName('accentSoft')).toBe('--color-accent-soft');
  });

  it('every palette token has a Tailwind colour and vice versa', () => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const config = require('../tailwind.config.js');
    const tw = Object.keys(config.theme.extend.colors).sort();
    const fromTokens = Object.keys(palettes.dark)
      .map((k) => cssVarName(k as keyof Palette).replace('--color-', ''))
      .sort();
    expect(tw).toEqual(fromTokens);
  });

  it('uses Bricolage Grotesque and Figtree only, matching Tailwind font families', () => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const config = require('../tailwind.config.js');
    const families = Object.values(config.theme.extend.fontFamily).flat();
    expect(families.sort()).toEqual(Object.values(fonts).sort());
    for (const f of Object.values(fonts)) expect(f).toMatch(/^(BricolageGrotesque|Figtree)_/);
  });
});

describe('no Marquee leftovers', () => {
  const root = path.join(__dirname, '..');
  const sources = (dir: string): string[] =>
    fs.readdirSync(path.join(root, dir), { withFileTypes: true }).flatMap((e) => {
      const rel = path.join(dir, e.name);
      if (e.isDirectory()) return sources(rel);
      return /\.(ts|tsx)$/.test(e.name) ? [rel] : [];
    });
  const files = ['app', 'components', 'auth', 'theme', 'lib'].flatMap(sources);

  it('no source uses the old fonts or gold/velvet tokens', () => {
    for (const f of files) {
      const src = fs.readFileSync(path.join(root, f), 'utf8');
      expect({ f, hit: /Fraunces|Inter_\d|\b(bg|text|border)-(on-)?(gold|velvet)\b|colors\.(gold|velvet|onGold|onVelvet)\b/.test(src) }).toEqual({ f, hit: false });
    }
  });

  it('every Switch goes through the themed Toggle (bug 8: default teal thumb)', () => {
    for (const f of files) {
      if (f.endsWith(path.join('ui', 'Toggle.tsx'))) continue;
      const src = fs.readFileSync(path.join(root, f), 'utf8');
      expect({ f, rawSwitch: /<Switch\b/.test(src) }).toEqual({ f, rawSwitch: false });
    }
  });

  it('the old font packages are gone from package.json', () => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const pkg = require('../package.json');
    expect(Object.keys(pkg.dependencies)).not.toContain('@expo-google-fonts/fraunces');
    expect(Object.keys(pkg.dependencies)).not.toContain('@expo-google-fonts/inter');
    expect(Object.keys(pkg.dependencies)).toEqual(
      expect.arrayContaining(['@expo-google-fonts/bricolage-grotesque', '@expo-google-fonts/figtree']),
    );
  });
});
