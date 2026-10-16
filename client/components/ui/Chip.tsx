import { Pressable, Text } from 'react-native';

type Props = {
  label: string;
  selected?: boolean;
  onPress?: () => void;
  className?: string;
};

/** Pill toggle, e.g. a genre filter. Gold when selected. */
export function Chip({ label, selected = false, onPress, className = '' }: Props) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected }}
      onPress={onPress}
      className={`min-h-9 items-center justify-center rounded-full border px-4 py-1.5 active:opacity-80 ${
        selected ? 'border-gold bg-gold' : 'border-border bg-surface-raised'
      } ${className}`}
    >
      <Text className={`font-body-medium text-sm ${selected ? 'text-on-gold' : 'text-text'}`}>{label}</Text>
    </Pressable>
  );
}
