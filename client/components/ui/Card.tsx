import { View, type ViewProps } from 'react-native';

export function Card({ className = '', raised = false, ...rest }: ViewProps & { className?: string; raised?: boolean }) {
  return (
    <View
      className={`rounded-2xl border border-border p-4 ${raised ? 'bg-surface-raised' : 'bg-surface'} ${className}`}
      {...rest}
    />
  );
}
