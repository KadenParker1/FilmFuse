import { Pressable, Text, View } from 'react-native';

export type Choice<T> = { value: T; label: string; accessibilityLabel?: string };

type Props<T> = {
  /** Accessible name for the group. */
  label: string;
  choices: Choice<T>[];
  value: T;
  onChange: (value: T) => void;
  /** `wrap` lays pills out on as many rows as needed; `fill` stretches them across one row. */
  layout?: 'wrap' | 'fill';
  disabled?: boolean;
  testID?: string;
  className?: string;
};

/** Single-choice pills that read as a radio group. Accent-filled when selected. */
export function ChoiceGroup<T extends string | number | null>({
  label,
  choices,
  value,
  onChange,
  layout = 'wrap',
  disabled = false,
  testID,
  className = '',
}: Props<T>) {
  return (
    <View
      testID={testID}
      accessibilityRole="radiogroup"
      accessibilityLabel={label}
      className={`flex-row gap-2 ${layout === 'wrap' ? 'flex-wrap' : ''} ${className}`}
    >
      {choices.map((c) => {
        const selected = c.value === value;
        return (
          <Pressable
            key={String(c.value)}
            accessibilityRole="radio"
            accessibilityLabel={c.accessibilityLabel ?? c.label}
            accessibilityState={{ checked: selected, disabled }}
            disabled={disabled}
            onPress={() => {
              if (!selected) onChange(c.value);
            }}
            className={`min-h-9 items-center justify-center rounded-xl px-3 py-1.5 active:opacity-80 ${
              layout === 'fill' ? 'flex-1' : ''
            } ${selected ? 'bg-accent' : 'bg-raised'} ${disabled ? 'opacity-50' : ''}`}
          >
            <Text className={`font-body-semibold text-sm ${selected ? 'text-on-accent' : 'text-text'}`}>{c.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}
