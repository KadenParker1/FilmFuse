import { View, type ViewProps } from 'react-native';

/** Flat surface with large corners. `raised` is one step lighter and borderless (nested cards). */
export function Card({ className = '', raised = false, ...rest }: ViewProps & { className?: string; raised?: boolean }) {
  return (
    <View
      className={`rounded-[18px] p-4 ${raised ? 'bg-raised' : 'border border-border bg-surface'} ${className}`}
      {...rest}
    />
  );
}
