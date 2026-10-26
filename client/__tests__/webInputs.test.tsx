/**
 * iOS Safari zooms the page when an input under 16px gets focus. Every text input must go through
 * components/ui/TextInput, which renders at >= 16px on web and leaves native sizing alone.
 */
import fs from 'fs';
import path from 'path';
import { render, screen } from '@testing-library/react-native';
import { Platform, StyleSheet } from 'react-native';
import { SearchBar } from '../components/ui/SearchBar';
import { TextField } from '../components/ui/TextField';
import {
  MIN_WEB_INPUT_FONT_SIZE,
  TextInput,
  classFontSize,
  webInputFontStyle,
} from '../components/ui/TextInput';

const ROOT = path.join(__dirname, '..');

function sourceFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(full);
    return /\.tsx?$/.test(entry.name) ? [full] : [];
  });
}

describe('web input font size (no iOS focus zoom)', () => {
  it('nothing outside components/ui/TextInput uses React Native TextInput directly', () => {
    const offenders: string[] = [];
    for (const dir of ['app', 'components', 'auth']) {
      for (const file of sourceFiles(path.join(ROOT, dir))) {
        const rel = path.relative(ROOT, file);
        if (rel === path.join('components', 'ui', 'TextInput.tsx')) continue;
        const source = fs.readFileSync(file, 'utf8');
        // Value imports of TextInput from react-native (type-only imports are fine).
        for (const match of source.matchAll(/import\s*\{([^}]*)\}\s*from\s*'react-native'/g)) {
          const names = match[1]!.split(',').map((n) => n.trim());
          if (names.some((n) => /^TextInput(\s+as\s+\w+)?$/.test(n))) offenders.push(rel);
        }
        if (/require\('react-native'\)\.TextInput|\bRN\.TextInput\b/.test(source)) offenders.push(rel);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('no input asks for a text class under 16px', () => {
    const offenders: string[] = [];
    for (const dir of ['app', 'components', 'auth']) {
      for (const file of sourceFiles(path.join(ROOT, dir))) {
        const source = fs.readFileSync(file, 'utf8');
        for (const match of source.matchAll(/<(TextInput|TextField|SearchBar)\b[^>]*?className="([^"]*)"/gs)) {
          const size = classFontSize(match[2]);
          if (size !== undefined && size < MIN_WEB_INPUT_FONT_SIZE) {
            offenders.push(`${path.relative(ROOT, file)}: <${match[1]} className="${match[2]}">`);
          }
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('reads the Tailwind text size of a class list', () => {
    expect(classFontSize('flex-1 font-body text-base text-text')).toBe(16);
    expect(classFontSize('text-sm')).toBe(14);
    expect(classFontSize('text-xs')).toBe(12);
    expect(classFontSize('text-2xl')).toBe(24);
    expect(classFontSize('text-[15px]')).toBe(15);
    expect(classFontSize('text-text text-muted')).toBeUndefined();
    expect(classFontSize(undefined)).toBeUndefined();
  });

  it('web: raises small or unknown sizes to 16 and keeps larger ones', () => {
    expect(webInputFontStyle(undefined, undefined, 'web')).toEqual({ fontSize: 16 });
    expect(webInputFontStyle({ fontSize: 12 }, undefined, 'web')).toEqual({ fontSize: 16 });
    expect(webInputFontStyle(undefined, 'text-sm', 'web')).toEqual({ fontSize: 16 });
    expect(webInputFontStyle(undefined, 'text-lg', 'web')).toEqual({ fontSize: 18 });
    expect(webInputFontStyle([{ fontSize: 20 }], 'text-sm', 'web')).toEqual({ fontSize: 20 });
  });

  it.each(['ios', 'android'])('%s: leaves the designed size alone', (os) => {
    expect(webInputFontStyle({ fontSize: 12 }, 'text-sm', os)).toBeUndefined();
  });

  describe('rendered on web', () => {
    beforeEach(() => jest.replaceProperty(Platform, 'OS', 'web'));
    afterEach(() => jest.restoreAllMocks());

    const fontSizeOf = (label: string) => StyleSheet.flatten(screen.getByLabelText(label).props.style)?.fontSize;

    it('every shared input renders at >= 16px', async () => {
      await render(
        <>
          <TextField label="Email" value="" onChangeText={() => {}} />
          <SearchBar label="Search movies" value="" onChangeText={() => {}} />
          <TextInput accessibilityLabel="Raw small" style={{ fontSize: 11 }} />
          <TextInput accessibilityLabel="Raw large" style={{ fontSize: 22 }} />
        </>,
      );
      for (const label of ['Email', 'Search movies', 'Raw small']) {
        expect(fontSizeOf(label)).toBeGreaterThanOrEqual(MIN_WEB_INPUT_FONT_SIZE);
      }
      expect(fontSizeOf('Raw large')).toBe(22);
    });
  });

  it('native: the style is passed through untouched', async () => {
    await render(<TextInput accessibilityLabel="Native" style={{ fontSize: 13 }} />);
    expect(StyleSheet.flatten(screen.getByLabelText('Native').props.style)?.fontSize).toBe(13);
  });
});
