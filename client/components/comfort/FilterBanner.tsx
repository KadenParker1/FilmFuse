import { Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../../theme/useTheme';
import type { EffectiveFilterDTO } from '../../types/api';

/** True when the effective filter lets everything through. */
export function isUnfiltered(filter: Pick<EffectiveFilterDTO, 'maxCertification' | 'contentLimits'>): boolean {
  return (
    filter.maxCertification === null &&
    Object.values(filter.contentLimits).every((level) => level === undefined || level === 'STRONG')
  );
}

/** The banner's text: "Filtered to …" with the server's label, or "No content limits". */
export function filterBannerText(filter: EffectiveFilterDTO): string {
  return isUnfiltered(filter) ? 'No content limits' : `Filtered to ${filter.label}`;
}

/**
 * The effective content filter for a group or session. Shows levels only — never whose settings
 * set them.
 */
export function FilterBanner({ filter, className = '' }: { filter: EffectiveFilterDTO; className?: string }) {
  const { colors } = useTheme();
  const text = filterBannerText(filter);
  return (
    <View
      testID="filter-banner"
      accessible
      accessibilityLabel={`${text}. Uses the most cautious settings of everyone here.`}
      className={`flex-row items-center gap-2 rounded-xl border border-border bg-surface-raised px-3 py-2 ${className}`}
    >
      <Ionicons name="shield-checkmark-outline" size={16} color={colors.gold} />
      <Text className="flex-1 font-body-medium text-sm text-text">{text}</Text>
    </View>
  );
}
