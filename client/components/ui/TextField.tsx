import { Text, TextInput, View, type TextInputProps } from 'react-native';
import { useTheme } from '../../theme/useTheme';

type Props = TextInputProps & {
  label: string;
  error?: string | null;
  hint?: string;
  className?: string;
};

export function TextField({ label, error, hint, className = '', ...rest }: Props) {
  const { colors } = useTheme();
  return (
    <View className={`gap-1.5 ${className}`}>
      <Text className="font-body-semibold text-sm text-text">{label}</Text>
      <TextInput
        accessibilityLabel={label}
        accessibilityHint={error ?? hint}
        placeholderTextColor={colors.muted}
        className={`min-h-12 rounded-xl border bg-raised px-4 py-3 font-body text-base text-text ${
          error ? 'border-danger' : 'border-transparent'
        }`}
        {...rest}
      />
      {error ? (
        <Text accessibilityLiveRegion="polite" className="font-body text-sm text-danger">
          {error}
        </Text>
      ) : hint ? (
        <Text className="font-body text-sm text-muted">{hint}</Text>
      ) : null}
    </View>
  );
}
