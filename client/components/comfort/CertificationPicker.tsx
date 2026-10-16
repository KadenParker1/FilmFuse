import { View } from 'react-native';
import type { Certification } from '../../types/api';
import { Body, ChoiceGroup, Label, type Choice } from '../ui';
import { CERTIFICATIONS, certificationDescription, certificationLabel } from './comfortCopy';

const CHOICES: Choice<Certification | null>[] = [
  { value: null, label: 'No limit' },
  ...CERTIFICATIONS.map((c) => ({ value: c, label: certificationLabel(c) })),
];

type Props = {
  value: Certification | null;
  onChange: (value: Certification | null) => void;
  /** Heading above the pills. */
  title?: string;
  disabled?: boolean;
};

/** "Highest rating you'll watch": No limit / G … NC-17, with a plain-language line for the choice. */
export function CertificationPicker({ value, onChange, title = 'Highest rating', disabled }: Props) {
  return (
    <View className="gap-2">
      <Label accessibilityRole="header">{title}</Label>
      <ChoiceGroup label={title} choices={CHOICES} value={value} onChange={onChange} disabled={disabled} />
      <Body muted testID="certification-description" className="text-sm">
        {certificationDescription(value)}
      </Body>
    </View>
  );
}
