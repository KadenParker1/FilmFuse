import type { ReactNode } from 'react';
import { ActivityIndicator, Pressable, Text, type PressableProps } from 'react-native';
import { useTheme } from '../../theme/useTheme';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

const containerByVariant: Record<ButtonVariant, string> = {
  primary: 'bg-accent',
  secondary: 'bg-raised',
  ghost: 'bg-transparent',
  danger: 'bg-danger',
};

const labelByVariant: Record<ButtonVariant, string> = {
  primary: 'text-on-accent',
  secondary: 'text-text',
  ghost: 'text-accent-ink',
  danger: 'text-on-danger',
};

type Props = Omit<PressableProps, 'children'> & {
  title: string;
  variant?: ButtonVariant;
  loading?: boolean;
  icon?: ReactNode;
  /** `sm` is the compact button used inside list rows. */
  size?: 'md' | 'sm';
  className?: string;
};

/** Technicolor button: flat fills, 14-16px corners (not pills), bold Figtree label. */
export function Button({
  title,
  variant = 'primary',
  loading = false,
  disabled,
  icon,
  size = 'md',
  className = '',
  ...rest
}: Props) {
  const { colors } = useTheme();
  const isDisabled = disabled || loading;
  const spinnerColor =
    variant === 'primary' ? colors.onAccent : variant === 'danger' ? colors.onDanger : variant === 'ghost' ? colors.accentInk : colors.text;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={title}
      accessibilityState={{ disabled: !!isDisabled, busy: loading }}
      disabled={isDisabled}
      className={`flex-row items-center justify-center active:opacity-80 ${
        size === 'sm' ? 'min-h-9 gap-1.5 rounded-xl px-3.5 py-1.5' : 'min-h-12 gap-2 rounded-2xl px-5 py-3'
      } ${containerByVariant[variant]} ${isDisabled ? 'opacity-50' : ''} ${className}`}
      {...rest}
    >
      {loading ? <ActivityIndicator size="small" color={spinnerColor} /> : icon}
      <Text className={`font-body-bold ${size === 'sm' ? 'text-sm' : 'text-base'} ${labelByVariant[variant]}`}>{title}</Text>
    </Pressable>
  );
}
