import { useState, type ComponentProps } from 'react';
import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import type { useCastVote, useClearVote, useRefreshSession } from '../../api/sessions';
import { pluralize } from '../../lib/format';
import { useReduceMotion } from '../../lib/useReduceMotion';
import { useTheme } from '../../theme/useTheme';
import type { SessionDTO, SessionResultDTO } from '../../types/api';
import { Body, Button, EmptyState, Heading, errorMessage } from '../ui';
import { MatchCard } from './MatchCard';
import { SwipeCard } from './SwipeCard';
import { TallyPanel, useLiveAnnouncement } from './TallyPanel';
import { buildDeck, deckProgress, newMatches, type SwipeVote } from './swipeDeckLogic';
import { computeTally } from './tally';

type Props = {
  session: SessionDTO;
  /** Shared with the list view and the screen's veto sheet, so one error state covers both. */
  vote: ReturnType<typeof useCastVote>;
  clear: ReturnType<typeof useClearVote>;
  refresh: ReturnType<typeof useRefreshSession>;
  /** Opens the screen's "Veto X?" confirmation (a veto is never a gesture). */
  onVeto: (result: SessionResultDTO) => void;
  /** Opens the screen's "Watch X?" pick confirmation. */
  onPick: (result: SessionResultDTO) => void;
  onOpenMovie: (movieId: number) => void;
  onShowList: () => void;
};

type IconName = ComponentProps<typeof Ionicons>['name'];

function RoundButton({
  label,
  icon,
  tone,
  disabled,
  onPress,
}: {
  label: string;
  icon: IconName;
  tone: 'up' | 'down' | 'veto' | 'undo';
  disabled?: boolean;
  onPress: () => void;
}) {
  const { colors } = useTheme();
  const color = tone === 'up' ? colors.accent : tone === 'down' || tone === 'veto' ? colors.danger : colors.text;
  const big = tone === 'up' || tone === 'down';
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: !!disabled }}
      disabled={disabled}
      onPress={onPress}
      className={`items-center gap-1 active:opacity-80 ${disabled ? 'opacity-40' : ''}`}
    >
      <View
        className={`items-center justify-center rounded-full border-2 bg-surface ${big ? 'h-16 w-16' : 'h-12 w-12'}`}
        style={{ borderColor: color }}
      >
        <Ionicons name={icon} size={big ? 28 : 20} color={color} />
      </View>
      <Text className="font-body-semibold text-xs text-muted">{label}</Text>
    </Pressable>
  );
}

/**
 * The open session as a fixed swipe deck: the non-vetoed results in ML rank order, minus the ones
 * I've already voted on (so reopening resumes). Right / Yes = thumbs-up, left / Nope = thumbs-down,
 * Veto goes through the screen's confirm sheet, Undo takes back the last swipe. When everyone has
 * swiped right on a movie a match card offers to pick it; at the end, the tally and More options.
 */
export function SwipeDeck({ session, vote, clear, refresh, onVeto, onPick, onOpenMovie, onShowList }: Props) {
  const { colors } = useTheme();
  const reduceMotion = useReduceMotion();
  // Swiped this visit: gone at once, before the optimistic cache update lands.
  const [hidden, setHidden] = useState<number[]>([]);
  const [lastSwiped, setLastSwiped] = useState<number | null>(null);
  const tally = computeTally(session.results, session.memberCount, session.votedCount);
  // Matches already there when the deck opened, or already shown, don't pop up (again).
  const [seenMatches, setSeenMatches] = useState<ReadonlySet<number>>(() => new Set(tally.everyoneLikes));

  const live = session.results.filter((r) => !r.votes.vetoed);
  const deck = buildDeck(session.results).filter((r) => !hidden.includes(r.movie.id));
  const top = deck[0];
  const position = live.length - deck.length;
  const progress = top ? deckProgress(position, live.length) : '';
  const matchId = newMatches(seenMatches, tally)[0];
  const match = matchId != null ? session.results.find((r) => r.movie.id === matchId) : undefined;
  const voteError = vote.isError ? vote.error : clear.isError ? clear.error : null;
  const waiting = Math.max(0, session.memberCount - session.votedCount);

  const announcement = match
    ? `Everyone’s in on ${match.movie.title}.`
    : top
      ? progress
      : live.length
        ? 'You’ve swiped them all.'
        : 'Nothing fits yet.';
  useLiveAnnouncement(announcement);

  const swipe = (result: SessionResultDTO, v: SwipeVote) => {
    const id = result.movie.id;
    setHidden((h) => [...h, id]);
    setLastSwiped(id);
    vote.mutate(
      { sessionId: session.id, movieId: id, vote: v },
      {
        // Rolled back: the card comes back (the error shows above the deck).
        onError: () => {
          setHidden((h) => h.filter((m) => m !== id));
          setLastSwiped((l) => (l === id ? null : l));
        },
      },
    );
  };

  const undo = () => {
    if (lastSwiped == null) return;
    clear.mutate({ sessionId: session.id, movieId: lastSwiped });
    setHidden((h) => h.filter((m) => m !== lastSwiped));
    setLastSwiped(null);
  };

  const keepSwiping = () => {
    if (matchId == null) return;
    setSeenMatches((s) => new Set([...s, matchId]));
  };

  let body;
  if (session.results.length === 0) {
    body = (
      <EmptyState
        icon="film-outline"
        title="Nothing fits yet"
        message="No movies match everyone’s filters. Try more options, or loosen the session filters."
      />
    );
  } else if (match) {
    body = (
      <MatchCard result={match} reduceMotion={reduceMotion} onPick={() => onPick(match)} onKeepSwiping={keepSwiping} />
    );
  } else if (top) {
    const next = deck[1];
    body = (
      <View testID="swipe-stack">
        {next ? (
          // The next card peeks out underneath.
          <View className="absolute inset-x-3 -bottom-2 top-2 overflow-hidden rounded-[22px] opacity-60" pointerEvents="none">
            <SwipeCard
              key={next.movie.id}
              result={next}
              members={session.members}
              progress=""
              interactive={false}
              reduceMotion={reduceMotion}
              onSwipe={() => {}}
              onVeto={() => {}}
              onOpenMovie={() => {}}
            />
          </View>
        ) : null}
        <SwipeCard
          key={top.movie.id}
          result={top}
          members={session.members}
          progress={progress}
          interactive
          reduceMotion={reduceMotion}
          onSwipe={(v) => swipe(top, v)}
          onVeto={() => onVeto(top)}
          onOpenMovie={() => onOpenMovie(top.movie.id)}
        />
      </View>
    );
  } else {
    body = (
      <View testID="deck-end" className="gap-4 rounded-[22px] border border-border bg-surface p-4">
        <View className="items-center gap-1">
          <Ionicons name="checkmark-done" size={28} color={colors.accent2} />
          <Heading size="sm" className="text-center">
            You’ve swiped them all
          </Heading>
          {waiting > 0 ? (
            <Body muted testID="deck-waiting" className="text-center text-sm">
              Waiting on {pluralize(waiting, 'person', 'people')}
            </Body>
          ) : null}
        </View>
        <TallyPanel tally={tally} onPickLeader={onPick} />
        {refresh.isError ? (
          <Body accessibilityRole="alert" className="text-sm text-danger">
            {errorMessage(refresh.error)}
          </Body>
        ) : null}
        <Button
          title="More options"
          variant="secondary"
          loading={refresh.isPending}
          onPress={() => refresh.mutate(session.id)}
        />
        <Button title="See the list" variant="ghost" onPress={onShowList} />
      </View>
    );
  }

  return (
    <View testID="swipe-deck" className="w-full max-w-[420px] gap-4 self-center">
      {/* Polite live region: Android and web read the match / end-of-deck changes (iOS is announced). */}
      <View accessibilityLiveRegion="polite" aria-live="polite" testID="deck-live-region" className="h-0 overflow-hidden">
        <Text>{announcement}</Text>
      </View>
      {voteError ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {errorMessage(voteError)}
        </Body>
      ) : null}
      {session.results.length === 0 ? (
        <>
          {body}
          <Button
            title="More options"
            variant="secondary"
            loading={refresh.isPending}
            onPress={() => refresh.mutate(session.id)}
          />
        </>
      ) : (
        body
      )}
      {session.results.length > 0 && !match ? (
        <View testID="swipe-buttons" className="flex-row items-end justify-center gap-5">
          <RoundButton label="Undo" icon="arrow-undo" tone="undo" disabled={lastSwiped == null} onPress={undo} />
          {top ? (
            <>
              <RoundButton label="Nope" icon="close" tone="down" onPress={() => swipe(top, 'DOWN')} />
              <RoundButton label="Veto" icon="hand-left" tone="veto" onPress={() => onVeto(top)} />
              <RoundButton label="Yes" icon="heart" tone="up" onPress={() => swipe(top, 'UP')} />
            </>
          ) : null}
        </View>
      ) : null}
    </View>
  );
}
