import type { ReactNode } from 'react';
import { Text } from 'react-native';
import { Card, Slider1to10 } from '../ui';

type Props = {
  label: string;
  value: number;
  onChange: (value: number) => void;
  /** Extra line under the slider (the Taste editor's stated vs learned). */
  children?: ReactNode;
  testID?: string;
};

/** One genre or mood: its name and a 1–10 slider. */
export function PrefSliderRow({ label, value, onChange, children, testID }: Props) {
  return (
    <Card testID={testID} className="gap-1">
      <Text className="font-body-semibold text-base text-text">{label}</Text>
      <Slider1to10 value={value} onChange={onChange} accessibilityLabel={label} />
      {children}
    </Card>
  );
}
