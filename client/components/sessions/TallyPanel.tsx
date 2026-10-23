import { useEffect, useRef } from 'react';
import { AccessibilityInfo, Platform, Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../../theme/useTheme';
import type { SessionResultDTO } from '../../types/api';
import { Body } from '../ui';
import { leaderNote, tallySummary, type Tally } from './tally';

/**
 * Announces `message` to screen readers whenever it changes after the first render. Android and
 * web read the polite live region the panel renders; iOS has no live regions, so it is announced
 * there explicitly.
 */
export function useLiveAnnouncement(message: string): void {
  const previous = useRef<string | null>(null);
  useEffect(() => {
    if (previous.current !== null && previous.current !== message && Platform.OS === 'ios') {
      AccessibilityInfo.announceForAccessibility(message);
    }
    previous.current = message;
  }, [message]);
}

type Props = {
  tally: Tally;
  /** "Pick the leader": opens the usual pick confirmation for the leader. */
  onPickLeader: (leader: SessionResultDTO) => void;
};

/**
 * The live standings above the results: who's leading (or tied, and how the tie is broken), an
 * "everyone likes it" marker, "Everyone's voted", and the "Pick the leader" shortcut. The cards
 * stay in rank order; this is an overlay.
 */
export function TallyPanel({ tally, onPickLeader }: Props) {
  const { colors } = useTheme();
  const summary = tallySummary(tally);
  useLiveAnnouncement(summary);
  const { leader } = tally;
  const note = leaderNote(tally);
  const everyoneLikesLeader = !!leader && tally.everyoneLikes.has(leader.movie.id);

  return (
    <View testID="tally-panel" className="gap-3 rounded-[18px] border border-border bg-surface p-4">
      {/* Polite live region: Android (accessibilityLiveRegion) and web (aria-live) read changes. */}
      <View accessibilityLiveRegion="polite" aria-live="polite" testID="tally-live-region">
        <Text className="font-body-semibold text-base text-text">{summary}</Text>
      </View>
      {tally.everyoneVoted ? (
        <View testID="everyone-voted" className="flex-row items-center gap-2 rounded-xl bg-raised px-3 py-2">
          <Ionicons name="sparkles" size={16} color={colors.accent2} />
          <Body className="flex-1 text-sm">Everyone’s voted — time to pick.</Body>
        </View>
      ) : null}
      {everyoneLikesLeader ? (
        <View testID="leader-everyone-likes" className="flex-row items-center gap-2">
          <Ionicons name="heart" size={16} color={colors.accent2} />
          <Body className="text-sm">Everyone gave it a thumbs-up.</Body>
        </View>
      ) : null}
      {note ? (
        <Body muted testID="tie-note" className="text-sm">
          {note}
        </Body>
      ) : null}
      {leader ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Pick the leader, ${leader.movie.title}`}
          onPress={() => onPickLeader(leader)}
          className="min-h-11 flex-row items-center justify-center gap-2 self-start rounded-xl bg-accent px-4 py-2.5 active:opacity-80"
        >
          <Ionicons name="trophy" size={16} color={colors.onAccent} />
          <Text className="font-body-bold text-sm text-on-accent">Pick the leader</Text>
        </Pressable>
      ) : null}
    </View>
  );
}
