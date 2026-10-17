import { Pressable, Text } from 'react-native';

type Props = {
  label: string;
  selected?: boolean;
  onPress?: () => void;
  className?: string;
};

/** Pill toggle, e.g. a genre filter. Outlined; filled with the text colour when selected. */
export function Chip({ label, selected = false, onPress, className = '' }: Props) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected }}
      onPress={onPress}
      className={`min-h-9 items-center justify-center rounded-full border px-4 py-1.5 active:opacity-80 ${
        selected ? 'border-text bg-text' : 'border-border bg-transparent'
      } ${className}`}
    >
      <Text className={`font-body-semibold text-sm ${selected ? 'text-bg' : 'text-muted'}`}>{label}</Text>
    </Pressable>
  );
}
