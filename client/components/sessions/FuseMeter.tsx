import { Text, View } from 'react-native';
import { personName } from '../../lib/format';
import { fusePalettes } from '../../theme/tokens';
import { useTheme } from '../../theme/useTheme';
import type { SessionMemberDTO, UserSummaryDTO } from '../../types/api';
import { Avatar } from '../ui';
import { formatScore } from './sessionCopy';

export type FuseSegment = { user: UserSummaryDTO; score: number; share: number; color: string };

/**
 * One segment per member with a score, sized by their share of the summed fit. Members keep the
 * session's member order (and so a stable Fuse colour, cycled for big groups); scores ≤ 0 or
 * missing get no segment.
 */
export function fuseSegments(
  members: SessionMemberDTO[],
  scores: Record<string, number>,
  palette: readonly string[] = fusePalettes.dark,
): FuseSegment[] {
  const scored: Omit<FuseSegment, 'share'>[] = [];
  members.forEach((m, i) => {
    const score = scores[m.user.id];
    if (typeof score === 'number' && score > 0) {
      scored.push({ user: m.user, score, color: palette[i % palette.length]! });
    }
  });
  const total = scored.reduce((sum, s) => sum + s.score, 0);
  return scored.map((s) => ({ ...s, share: total > 0 ? s.score / total : 0 }));
}

/** "Fit for each person: Ada 8 out of 10, Grace 4.5 out of 10". */
export function fuseMeterLabel(segments: FuseSegment[]): string {
  if (segments.length === 0) return 'No fit scores yet';
  return `Fit for each person: ${segments.map((s) => `${personName(s.user)} ${formatScore(s.score)} out of 10`).join(', ')}`;
}

/** First word of the person's name, for the compact legend. */
const shortName = (u: UserSummaryDTO) => personName(u).split(/\s+/)[0] ?? personName(u);

/**
 * The "Fuse meter": a thick stacked bar in each member's Fuse colour, sized by their fit, with a
 * legend (avatar ringed in that colour + score) so the group can see who a movie is for.
 */
export function FuseMeter({ members, scores }: { members: SessionMemberDTO[]; scores: Record<string, number> }) {
  const { fuse } = useTheme();
  const segments = fuseSegments(members, scores, fuse);
  return (
    <View testID="fuse-meter" accessible accessibilityRole="image" accessibilityLabel={fuseMeterLabel(segments)} className="gap-2">
      {segments.length === 0 ? (
        <View testID="fuse-empty" className="h-3 w-full rounded-full bg-raised" />
      ) : (
        <View className="h-3 w-full flex-row gap-0.5 overflow-hidden rounded-full">
          {segments.map((s) => (
            <View
              key={s.user.id}
              testID={`fuse-segment-${s.user.id}`}
              className="h-full"
              style={{ flexGrow: s.share, flexBasis: 0, backgroundColor: s.color }}
            />
          ))}
        </View>
      )}
      {segments.length === 0 ? (
        <Text className="font-body text-xs text-muted">No fit scores yet</Text>
      ) : (
        <View className="flex-row flex-wrap gap-x-3 gap-y-1">
          {segments.map((s) => (
            <View key={s.user.id} testID={`fuse-legend-${s.user.id}`} className="flex-row items-center gap-1.5">
              <View className="rounded-full p-0.5" style={{ backgroundColor: s.color }}>
                <Avatar uri={s.user.profileImageUrl} displayName={s.user.displayName} username={s.user.username} size={18} />
              </View>
              <Text className="font-body-semibold text-xs text-muted" numberOfLines={1}>
                {shortName(s.user)} {formatScore(s.score)}
              </Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );
}
