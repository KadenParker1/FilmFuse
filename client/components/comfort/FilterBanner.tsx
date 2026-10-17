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

/**
 * The banner's text: the server's label, verbatim. It is self-contained ("Rated PG-13 or below ·
 * mild violence at most", "No limits"), so the client adds no prefix - one source of truth.
 */
export function filterBannerText(filter: EffectiveFilterDTO): string {
  return filter.label;
}

/**
 * The effective content filter for a group or session, as a pill with a shield. Shows levels only —
 * never whose settings set them.
 */
export function FilterBanner({ filter, className = '' }: { filter: EffectiveFilterDTO; className?: string }) {
  const { colors } = useTheme();
  const text = filterBannerText(filter);
  const open = isUnfiltered(filter);
  return (
    <View
      testID="filter-banner"
      accessible
      accessibilityLabel={`${text}. Uses the most cautious settings of everyone here.`}
      className={`flex-row items-center gap-2 self-start rounded-full border border-border px-3 py-1.5 ${className}`}
    >
      <Ionicons name={open ? 'shield-outline' : 'shield-checkmark'} size={14} color={open ? colors.muted : colors.accentInk} />
      <Text className="shrink font-body-medium text-sm text-muted">{text}</Text>
    </View>
  );
}
