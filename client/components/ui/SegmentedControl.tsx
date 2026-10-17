import { Pressable, Text, View } from 'react-native';

export type Segment<T extends string> = {
  value: T;
  label: string;
  /** A small count pill after the label (e.g. pending requests); hidden at 0. */
  badge?: number;
};

type Props<T extends string> = {
  segments: Segment<T>[];
  value: T;
  onChange: (value: T) => void;
  /** Accessible name for the whole control. */
  label: string;
  className?: string;
};

/** Pill-shaped tab switcher on a surface track; the selected segment is filled with the text colour. */
export function SegmentedControl<T extends string>({ segments, value, onChange, label, className = '' }: Props<T>) {
  return (
    <View
      accessibilityRole="tablist"
      accessibilityLabel={label}
      className={`flex-row gap-0.5 rounded-full border border-border bg-surface p-1 ${className}`}
    >
      {segments.map((s) => {
        const selected = s.value === value;
        const badge = s.badge && s.badge > 0 ? s.badge : 0;
        return (
          <Pressable
            key={s.value}
            accessibilityRole="tab"
            accessibilityLabel={badge ? `${s.label}, ${badge} new` : s.label}
            accessibilityState={{ selected }}
            onPress={() => {
              if (!selected) onChange(s.value);
            }}
            className={`min-h-10 flex-1 flex-row items-center justify-center gap-2 rounded-full px-4 py-2 active:opacity-80 ${
              selected ? 'bg-text' : ''
            }`}
          >
            <Text className={`font-body-semibold text-sm ${selected ? 'text-bg' : 'text-muted'}`}>{s.label}</Text>
            {badge ? (
              <View testID={`segment-badge-${s.value}`} className="min-w-5 items-center rounded-full bg-accent2 px-1.5">
                <Text className="font-body-bold text-xs text-on-accent2">{badge > 9 ? '9+' : badge}</Text>
              </View>
            ) : null}
          </Pressable>
        );
      })}
    </View>
  );
}
