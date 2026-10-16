import { Pressable, TextInput, View, type TextInputProps } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../../theme/useTheme';

type Props = Omit<TextInputProps, 'value' | 'onChangeText'> & {
  value: string;
  onChangeText: (text: string) => void;
  /** Accessible name for the input. */
  label?: string;
  className?: string;
};

/** Rounded search input with a magnifier and a clear button once there's text. */
export function SearchBar({ value, onChangeText, label = 'Search', placeholder, className = '', ...rest }: Props) {
  const { colors } = useTheme();
  return (
    <View
      className={`min-h-12 flex-row items-center gap-2 rounded-full border border-border bg-surface px-4 ${className}`}
    >
      <Ionicons name="search" size={18} color={colors.textMuted} />
      <TextInput
        accessibilityLabel={label}
        accessibilityRole="search"
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder ?? label}
        placeholderTextColor={colors.textMuted}
        autoCapitalize="none"
        autoCorrect={false}
        returnKeyType="search"
        className="flex-1 py-3 font-body text-base text-text"
        {...rest}
      />
      {value.length > 0 ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Clear search"
          hitSlop={8}
          onPress={() => onChangeText('')}
          className="active:opacity-60"
        >
          <Ionicons name="close-circle" size={18} color={colors.textMuted} />
        </Pressable>
      ) : null}
    </View>
  );
}
