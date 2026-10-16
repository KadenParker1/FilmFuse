import Slider from '@react-native-community/slider';
import { Text, View } from 'react-native';
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

/** Integer 1–10 slider with the current score shown large and a live word label. */
export function Slider1to10({ value, onChange, accessibilityLabel, disabled, className = '' }: Props) {
  const { colors } = useTheme();
  const score = clampScore(value);
  const label = scoreLabel(score);

  return (
    <View className={`gap-2 ${className}`}>
      <View className="flex-row items-baseline gap-3">
        <Text testID="slider-value" className="font-display-bold text-5xl text-gold">
          {score}
        </Text>
        <Text testID="slider-label" accessibilityLiveRegion="polite" className="font-body-medium text-lg text-text-muted">
          {label}
        </Text>
      </View>
      <Slider
        accessibilityLabel={accessibilityLabel}
        accessibilityValue={{ min: SCORE_MIN, max: SCORE_MAX, now: score, text: `${score} out of 10, ${label}` }}
        minimumValue={SCORE_MIN}
        maximumValue={SCORE_MAX}
        step={1}
        value={score}
        disabled={disabled}
        onValueChange={(v) => {
          const next = clampScore(v);
          if (next !== score) onChange(next);
        }}
        minimumTrackTintColor={colors.gold}
        maximumTrackTintColor={colors.border}
        thumbTintColor={colors.gold}
        style={{ width: '100%', height: 40 }}
      />
      <View className="flex-row justify-between">
        <Text className="font-body text-xs text-text-muted">{SCORE_MIN}</Text>
        <Text className="font-body text-xs text-text-muted">{SCORE_MAX}</Text>
      </View>
    </View>
  );
}
