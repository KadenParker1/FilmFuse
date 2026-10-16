import { Text, View } from 'react-native';
import type { Certification } from '../../types/api';
import { certificationLabel } from '../comfort/comfortCopy';

/** A small outlined rating badge ("PG-13"); "NR" when the certification is unknown. */
export function CertificationBadge({ certification }: { certification: Certification | null }) {
  const label = certification ? certificationLabel(certification) : 'NR';
  return (
    <View
      testID="certification-badge"
      accessible
      accessibilityLabel={certification ? `Rated ${label}` : 'Not rated'}
      className="self-start rounded border border-text-muted px-1.5 py-0.5"
    >
      <Text className="font-body-semibold text-xs text-text-muted">{label}</Text>
    </View>
  );
}
