import { Text, View } from 'react-native';
import { personName } from '../../lib/format';
import type { SessionMemberDTO, UserSummaryDTO } from '../../types/api';
import { Avatar } from '../ui';
import { GROUP_COLORS } from '../groups/groupStyle';
import { formatScore } from './sessionCopy';

export type FuseSegment = { user: UserSummaryDTO; score: number; share: number; color: string };

/**
 * One segment per member with a score, sized by their share of the summed fit. Members keep the
 * session's member order (and so a stable colour); scores ≤ 0 or missing get no segment.
 */
export function fuseSegments(members: SessionMemberDTO[], scores: Record<string, number>): FuseSegment[] {
  const scored: Omit<FuseSegment, 'share'>[] = [];
  members.forEach((m, i) => {
    const score = scores[m.user.id];
    if (typeof score === 'number' && score > 0) {
      scored.push({ user: m.user, score, color: GROUP_COLORS[i % GROUP_COLORS.length]!.hex });
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

/** Avatars only fit inside segments wider than this share. */
const AVATAR_MIN_SHARE = 0.12;

/**
 * The "Fuse meter": a stacked bar of member photos sized by each person's fit, so the group can see
 * who a movie is for.
 */
export function FuseMeter({ members, scores }: { members: SessionMemberDTO[]; scores: Record<string, number> }) {
  const segments = fuseSegments(members, scores);
  return (
    <View
      testID="fuse-meter"
      accessible
      accessibilityRole="image"
      accessibilityLabel={fuseMeterLabel(segments)}
      className="h-7 w-full flex-row overflow-hidden rounded-full border border-border bg-surface-raised"
    >
      {segments.length === 0 ? (
        <Text className="self-center px-3 font-body text-xs text-text-muted">No fit scores yet</Text>
      ) : (
        segments.map((s) => (
          <View
            key={s.user.id}
            testID={`fuse-segment-${s.user.id}`}
            className="h-full items-center justify-center"
            style={{ flexGrow: s.share, flexBasis: 0, backgroundColor: s.color }}
          >
            {s.share >= AVATAR_MIN_SHARE ? (
              <Avatar
                uri={s.user.profileImageUrl}
                displayName={s.user.displayName}
                username={s.user.username}
                size={20}
              />
            ) : null}
          </View>
        ))
      )}
    </View>
  );
}
