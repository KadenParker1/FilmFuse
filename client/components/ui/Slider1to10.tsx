import { useState } from 'react';
import Slider from '@react-native-community/slider';
import { Platform, Text, View, type AccessibilityActionEvent } from 'react-native';
import { useTheme } from '../../theme/useTheme';
import { clampScore, scoreLabel, SCORE_MAX, SCORE_MIN } from './scoreLabel';

export { scoreLabel } from './scoreLabel';

type Props = {
  value: number;
  onChange: (value: number) => void;
  /** What is being scored, e.g. "Your score for Dune" or "Sci-fi". Read by screen readers. */
  accessibilityLabel: string;
  disabled?: boolean;
  className?: string;
};

/** How far PageUp / PageDown move on the 1–10 scale. */
export const SLIDER_PAGE_STEP = 3;

/**
 * The score a key press moves to, or null for keys the slider doesn't handle. Follows the WAI-ARIA
 * slider pattern: arrows step by one, PageUp/PageDown by SLIDER_PAGE_STEP, Home/End jump to the ends.
 */
export function scoreForKey(key: string, score: number): number | null {
  switch (key) {
    case 'ArrowRight':
    case 'ArrowUp':
      return clampScore(score + 1);
    case 'ArrowLeft':
    case 'ArrowDown':
      return clampScore(score - 1);
    case 'PageUp':
      return clampScore(score + SLIDER_PAGE_STEP);
    case 'PageDown':
      return clampScore(score - SLIDER_PAGE_STEP);
    case 'Home':
      return SCORE_MIN;
    case 'End':
      return SCORE_MAX;
    default:
      return null;
  }
}

type KeyEvent = { nativeEvent?: { key?: string }; key?: string; preventDefault?: () => void };

/**
 * Integer 1–10 slider with the current score shown large and a live word label.
 *
 * Accessibility lives on the wrapper, not the community slider (which on web is neither focusable
 * nor exposed with its value): it is an "adjustable" element (role slider on web) with the value
 * range and text, increment/decrement actions for VoiceOver/TalkBack swipes, and on web it takes
 * focus and handles Arrow / PageUp / PageDown / Home / End. The drag track underneath is hidden from
 * assistive tech so the score is announced once.
 */
export function Slider1to10({ value, onChange, accessibilityLabel, disabled, className = '' }: Props) {
  const { colors } = useTheme();
  const [focused, setFocused] = useState(false);
  const score = clampScore(value);
  const label = scoreLabel(score);
  const valueText = `${score} out of 10, ${label}`;

  const set = (next: number) => {
    if (!disabled && next !== score) onChange(next);
  };

  const onAccessibilityAction = (e: AccessibilityActionEvent) => {
    if (e.nativeEvent.actionName === 'increment') set(clampScore(score + 1));
    else if (e.nativeEvent.actionName === 'decrement') set(clampScore(score - 1));
  };

  const onKeyDown = (e: KeyEvent) => {
    const key = e.nativeEvent?.key ?? e.key ?? '';
    const next = scoreForKey(key, score);
    if (next == null) return;
    e.preventDefault?.(); // arrows / PageUp / Home would otherwise scroll the page
    set(next);
  };

  // Web reads ARIA props; native reads the accessibility* ones (role="slider" would also work there,
  // but "adjustable" is what iOS/Android expose for swipe up/down).
  const webA11y =
    Platform.OS === 'web'
      ? ({
          role: 'slider' as const,
          'aria-label': accessibilityLabel,
          'aria-valuemin': SCORE_MIN,
          'aria-valuemax': SCORE_MAX,
          'aria-valuenow': score,
          'aria-valuetext': valueText,
          'aria-disabled': !!disabled,
          tabIndex: (disabled ? -1 : 0) as 0 | -1,
          // react-native-web forwards onKeyDown on View; React Native's types don't declare it.
          onKeyDown,
        } as object)
      : {};

  return (
    <View className={`gap-2 ${className}`}>
      <View className="flex-row items-baseline gap-3">
        <Text testID="slider-value" className="font-display-bold text-5xl text-accent-ink">
          {score}
        </Text>
        <Text testID="slider-label" accessibilityLiveRegion="polite" className="font-body-medium text-lg text-muted">
          {label}
        </Text>
      </View>
      <View
        testID="slider-1to10"
        accessible
        focusable={!disabled}
        accessibilityRole="adjustable"
        accessibilityLabel={accessibilityLabel}
        accessibilityValue={{ min: SCORE_MIN, max: SCORE_MAX, now: score, text: valueText }}
        accessibilityState={{ disabled: !!disabled }}
        accessibilityActions={[
          { name: 'increment', label: 'increment' },
          { name: 'decrement', label: 'decrement' },
        ]}
        onAccessibilityAction={onAccessibilityAction}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        className={`rounded-xl px-1 ${focused ? 'border-2 border-accent' : 'border-2 border-transparent'}`}
        {...webA11y}
      >
        <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants" aria-hidden>
          <Slider
            testID="slider-track"
            accessible={false}
            minimumValue={SCORE_MIN}
            maximumValue={SCORE_MAX}
            step={1}
            value={score}
            disabled={disabled}
            onValueChange={(v) => set(clampScore(v))}
            minimumTrackTintColor={colors.accent}
            maximumTrackTintColor={colors.border}
            thumbTintColor={colors.accent}
            style={{ width: '100%', height: 40 }}
          />
        </View>
      </View>
      <View className="flex-row justify-between">
        <Text className="font-body text-xs text-muted">{SCORE_MIN}</Text>
        <Text className="font-body text-xs text-muted">{SCORE_MAX}</Text>
      </View>
    </View>
  );
}
