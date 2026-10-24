import type { ReactNode } from 'react';
import { Text, View } from 'react-native';
import { Card } from '../ui';

type Tone = 'default' | 'good' | 'warn' | 'bad';

const valueTone: Record<Tone, string> = {
  default: 'text-text',
  good: 'text-success',
  warn: 'text-accent2',
  bad: 'text-danger',
};

/**
 * One number with its label. Read as a single element by screen readers ("Users, 1,204, 38 new this
 * week"). Grows to fill a row; wraps to its own line on narrow screens (min width).
 */
export function MetricCard({
  label,
  value,
  hint,
  tone = 'default',
  testID,
}: {
  label: string;
  value: string;
  hint?: string | null;
  tone?: Tone;
  testID?: string;
}) {
  return (
    <Card
      testID={testID}
      accessible
      accessibilityLabel={[label, value, hint].filter(Boolean).join(', ')}
      className="min-w-[150px] flex-1 gap-1"
    >
      <Text className="font-body-semibold text-sm text-muted">{label}</Text>
      <Text className={`font-display-bold text-3xl tracking-tight ${valueTone[tone]}`}>{value}</Text>
      {hint ? <Text className="font-body text-sm text-muted">{hint}</Text> : null}
    </Card>
  );
}

/** A wrapping row of metric cards. */
export function MetricRow({ children }: { children: ReactNode }) {
  return <View className="flex-row flex-wrap gap-3">{children}</View>;
}
