import type { Ref } from 'react';
import {
  Platform,
  StyleSheet,
  TextInput as RNTextInput,
  type StyleProp,
  type TextInputInstance,
  type TextInputProps as RNTextInputProps,
  type TextStyle,
} from 'react-native';

/**
 * iOS Safari zooms the page in when an input with a font size under 16px gets focus (and doesn't
 * zoom back out). Zoom stays allowed for accessibility, so instead every text input renders at
 * >= 16px on web.
 */
export const MIN_WEB_INPUT_FONT_SIZE = 16;

/** Tailwind's font-size scale (px) for the `text-*` classes an input might carry. */
const TEXT_CLASS_SIZES: Record<string, number> = {
  xs: 12,
  sm: 14,
  base: 16,
  lg: 18,
  xl: 20,
  '2xl': 24,
  '3xl': 30,
  '4xl': 36,
};

/** The font size (px) a `text-xs` / `text-lg` / `text-[15px]` class asks for, if any. */
export function classFontSize(className: string | undefined): number | undefined {
  if (!className) return undefined;
  let size: number | undefined;
  for (const token of className.split(/\s+/)) {
    const named = /^text-(xs|sm|base|lg|[234]?xl)$/.exec(token)?.[1];
    if (named) size = TEXT_CLASS_SIZES[named];
    const arbitrary = /^text-\[(\d+(?:\.\d+)?)px\]$/.exec(token);
    if (arbitrary) size = Number(arbitrary[1]);
  }
  return size;
}

/**
 * The web-only style that keeps an input at >= 16px: an explicit font size of the larger of 16
 * and whatever the style / class asked for (react-native-web's own TextInput default is 14px).
 * Native: nothing - phone sizing stays exactly as designed.
 */
export function webInputFontStyle(
  style: StyleProp<TextStyle>,
  className?: string,
  os: string = Platform.OS,
): TextStyle | undefined {
  if (os !== 'web') return undefined;
  const fromStyle = StyleSheet.flatten(style)?.fontSize;
  const requested = typeof fromStyle === 'number' ? fromStyle : classFontSize(className);
  return { fontSize: Math.max(requested ?? MIN_WEB_INPUT_FONT_SIZE, MIN_WEB_INPUT_FONT_SIZE) };
}

export type TextInputProps = RNTextInputProps & { className?: string; ref?: Ref<TextInputInstance> };

/**
 * The app's only TextInput: React Native's, plus the web font-size floor above. Use it instead of
 * importing TextInput from react-native (__tests__/webInputs.test.tsx enforces that).
 */
export function TextInput({ style, className, ref, ...rest }: TextInputProps) {
  const webFont = webInputFontStyle(style, className);
  return <RNTextInput ref={ref} className={className} style={webFont ? [style, webFont] : style} {...rest} />;
}
