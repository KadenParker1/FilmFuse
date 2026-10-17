import { Pressable, Text, View } from 'react-native';
import { scoreLabel, SCORE_MAX, SCORE_MIN } from '../ui';

const SCORES = Array.from({ length: SCORE_MAX - SCORE_MIN + 1 }, (_, i) => SCORE_MIN + i);

type Props = {
  value: number | null;
  onChange: (score: number) => void;
  disabled?: boolean;
};

/** Ten big 1–10 buttons in one tap row, with the chosen score's word label underneath. */
export function ScoreTapRow({ value, onChange, disabled }: Props) {
  return (
    <View className="gap-3">
      <View accessibilityRole="radiogroup" accessibilityLabel="Your score" className="flex-row gap-1.5">
        {SCORES.map((s) => {
          const selected = s === value;
          return (
            <Pressable
              key={s}
              accessibilityRole="radio"
              accessibilityLabel={`${s} out of 10, ${scoreLabel(s)}`}
              accessibilityState={{ checked: selected, disabled }}
              disabled={disabled}
              onPress={() => onChange(s)}
              className={`h-12 flex-1 items-center justify-center rounded-xl border active:opacity-80 ${
                selected ? 'border-accent bg-accent' : 'border-border bg-raised'
              }`}
            >
              <Text className={`font-body-semibold text-lg ${selected ? 'text-on-accent' : 'text-text'}`}>{s}</Text>
            </Pressable>
          );
        })}
      </View>
      <Text
        testID="score-label"
        accessibilityLiveRegion="polite"
        className="text-center font-body-medium text-base text-muted"
      >
        {value != null ? `${value}/10 · ${scoreLabel(value)}` : 'Tap a score'}
      </Text>
    </View>
  );
}
