import type { ReactNode } from 'react';
import { ActivityIndicator, Pressable, Text, type PressableProps } from 'react-native';
import { useTheme } from '../../theme/useTheme';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

const containerByVariant: Record<ButtonVariant, string> = {
  primary: 'bg-gold',
  secondary: 'bg-surface-raised border border-border',
  ghost: 'bg-transparent',
  danger: 'bg-velvet',
};

const labelByVariant: Record<ButtonVariant, string> = {
  primary: 'text-on-gold',
  secondary: 'text-text',
  ghost: 'text-gold',
  danger: 'text-on-velvet',
};

type Props = Omit<PressableProps, 'children'> & {
  title: string;
  variant?: ButtonVariant;
  loading?: boolean;
  icon?: ReactNode;
  className?: string;
};

export function Button({ title, variant = 'primary', loading = false, disabled, icon, className = '', ...rest }: Props) {
  const { colors } = useTheme();
  const isDisabled = disabled || loading;
  const spinnerColor = variant === 'primary' ? colors.onGold : variant === 'danger' ? colors.onVelvet : colors.text;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={title}
      accessibilityState={{ disabled: !!isDisabled, busy: loading }}
      disabled={isDisabled}
      className={`min-h-12 flex-row items-center justify-center gap-2 rounded-full px-5 py-3 active:opacity-80 ${
        containerByVariant[variant]
      } ${isDisabled ? 'opacity-50' : ''} ${className}`}
      {...rest}
    >
      {loading ? <ActivityIndicator size="small" color={spinnerColor} /> : icon}
      <Text className={`font-body-semibold text-base ${labelByVariant[variant]}`}>{title}</Text>
    </Pressable>
  );
}
