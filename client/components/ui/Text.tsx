import { Text, type TextProps } from 'react-native';

type Props = TextProps & { className?: string };

const headingSizes = {
  xl: 'text-4xl leading-[44px]',
  lg: 'text-3xl leading-9',
  md: 'text-2xl leading-8',
  sm: 'text-xl leading-7',
} as const;

/** Display heading in Bricolage Grotesque (800, tight tracking). */
export function Heading({
  className = '',
  size = 'lg',
  ...rest
}: Props & { size?: keyof typeof headingSizes }) {
  return (
    <Text accessibilityRole="header" className={`font-display-bold tracking-tight text-text ${headingSizes[size]} ${className}`} {...rest} />
  );
}

/** Body copy in Figtree. */
export function Body({ className = '', muted = false, ...rest }: Props & { muted?: boolean }) {
  return (
    <Text className={`font-body text-base leading-6 ${muted ? 'text-muted' : 'text-text'} ${className}`} {...rest} />
  );
}

/** Small uppercase-ish label text. */
export function Label({ className = '', ...rest }: Props) {
  return <Text className={`font-body-semibold text-sm text-muted ${className}`} {...rest} />;
}
