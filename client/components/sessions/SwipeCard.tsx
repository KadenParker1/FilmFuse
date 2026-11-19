import { useEffect, useState } from 'react';
import {
  Animated,
  PanResponder,
  Platform,
  Pressable,
  Text,
  View,
  type AccessibilityActionEvent,
  type LayoutChangeEvent,
} from 'react-native';
import type { SessionMemberDTO, SessionResultDTO } from '../../types/api';
import { Body, PosterCard } from '../ui';
import { FuseMeter } from './FuseMeter';
import { SWIPE_DISTANCE_RATIO, swipeCardLabel, swipeDecision, type SwipeVote } from './swipeDeckLogic';

/** Used until the card has been laid out (and under test, where nothing is). */
const FALLBACK_WIDTH = 320;
/** Horizontal movement before the card claims the gesture; vertical drags keep scrolling the page. */
const CLAIM_DX = 8;

type Props = {
  result: SessionResultDTO;
  members: SessionMemberDTO[];
  /** "Card 1 of 5", read at the end of the card's spoken label. */
  progress: string;
  /** Only the top card takes gestures and screen-reader focus; the one behind just peeks. */
  interactive: boolean;
  /** Cross-fade instead of fly and tilt. */
  reduceMotion: boolean;
  onSwipe: (vote: SwipeVote) => void;
  onVeto: () => void;
  onOpenMovie: () => void;
};

const useNativeDriver = Platform.OS !== 'web';

type Latest = { interactive: boolean; reduceMotion: boolean; width: number; onSwipe: (vote: SwipeVote) => void };

/** What the gesture handlers need from the latest render. */
class LatestProps {
  private value: Latest;
  constructor(value: Latest) {
    this.value = value;
  }
  get(): Latest {
    return this.value;
  }
  set(value: Latest): void {
    this.value = value;
  }
}

/**
 * Drag right / left past the threshold (or flick) to vote; a short drag springs back. The card flies
 * off before the vote is handed over; under reduce motion it is handed over at once.
 */
function createSwipeResponder(x: Animated.Value, latest: LatestProps) {
  const springBack = () => Animated.spring(x, { toValue: 0, friction: 6, useNativeDriver }).start();
  return PanResponder.create({
    onMoveShouldSetPanResponder: (_e, g) =>
      latest.get().interactive && Math.abs(g.dx) > CLAIM_DX && Math.abs(g.dx) > Math.abs(g.dy),
    onPanResponderTerminationRequest: () => false,
    onPanResponderMove: (_e, g) => x.setValue(g.dx),
    onPanResponderRelease: (_e, g) => {
      const { width, reduceMotion, onSwipe } = latest.get();
      const vote = swipeDecision(g.dx, g.vx, width);
      if (!vote) {
        springBack();
        return;
      }
      if (reduceMotion) {
        x.setValue(0);
        onSwipe(vote);
        return;
      }
      Animated.timing(x, { toValue: (vote === 'UP' ? 1 : -1) * width * 1.5, duration: 180, useNativeDriver }).start(() => {
        x.setValue(0);
        onSwipe(vote);
      });
    },
    onPanResponderTerminate: springBack,
  });
}

/**
 * One movie in the swipe deck: a big poster, the Fuse meter and why it was picked. Drag right for
 * a thumbs-up, left for a thumbs-down (React Native's PanResponder + Animated, so it works the same
 * on web and native); a short drag springs back. Screen readers get the card as one element with
 * Thumbs up / Thumbs down / Veto / Details actions.
 */
export function SwipeCard({ result, members, progress, interactive, reduceMotion, onSwipe, onVeto, onOpenMovie }: Props) {
  const { movie } = result;
  const [x] = useState(() => new Animated.Value(0));
  const [appear] = useState(() => new Animated.Value(interactive && reduceMotion ? 0 : 1));
  const [width, setWidth] = useState(FALLBACK_WIDTH);

  // The responder is created once (a new one mid-drag would lose the gesture); its handlers read
  // the latest props from this holder, which is only updated in an effect.
  const [latest] = useState(() => new LatestProps({ interactive, reduceMotion, width, onSwipe }));
  useEffect(() => {
    latest.set({ interactive, reduceMotion, width, onSwipe });
  });

  // Reduce motion: the card that comes to the top fades in instead of sliding.
  useEffect(() => {
    if (!interactive) return;
    if (!reduceMotion) {
      appear.setValue(1);
      return;
    }
    const anim = Animated.timing(appear, { toValue: 1, duration: 180, useNativeDriver });
    anim.start();
    return () => anim.stop();
  }, [interactive, reduceMotion, appear]);

  const [responder] = useState(() => createSwipeResponder(x, latest));

  const onLayout = (e: LayoutChangeEvent) => {
    const w = e.nativeEvent.layout.width;
    if (w > 0) setWidth(w);
  };

  const threshold = width * SWIPE_DISTANCE_RATIO;
  const yesOpacity = x.interpolate({ inputRange: [0, threshold], outputRange: [0, 1], extrapolate: 'clamp' });
  const nopeOpacity = x.interpolate({ inputRange: [-threshold, 0], outputRange: [1, 0], extrapolate: 'clamp' });
  const dragFade = x.interpolate({ inputRange: [-width, 0, width], outputRange: [0.4, 1, 0.4], extrapolate: 'clamp' });
  const opacity = Animated.multiply(dragFade, appear);
  const motion = reduceMotion
    ? {}
    : {
        transform: [
          { translateX: x },
          {
            rotate: x.interpolate({ inputRange: [-width, 0, width], outputRange: ['-12deg', '0deg', '12deg'] }),
          },
        ],
      };

  const onAccessibilityAction = (e: AccessibilityActionEvent) => {
    switch (e.nativeEvent.actionName) {
      case 'thumbsUp':
        return onSwipe('UP');
      case 'thumbsDown':
        return onSwipe('DOWN');
      case 'veto':
        return onVeto();
      case 'details':
      case 'activate':
        return onOpenMovie();
    }
  };

  const a11y = interactive
    ? {
        accessible: true,
        accessibilityLabel: swipeCardLabel(result, progress),
        accessibilityHint: 'Swipe right for thumbs up, left for thumbs down, or use the actions.',
        accessibilityActions: [
          { name: 'thumbsUp', label: 'Thumbs up' },
          { name: 'thumbsDown', label: 'Thumbs down' },
          { name: 'veto', label: 'Veto' },
          { name: 'details', label: 'Details' },
          { name: 'activate', label: 'Details' },
        ],
        onAccessibilityAction,
      }
    : { accessibilityElementsHidden: true, importantForAccessibility: 'no-hide-descendants' as const };

  return (
    <Animated.View
      testID={`swipe-card-${movie.id}`}
      onLayout={onLayout}
      {...a11y}
      {...(interactive ? responder.panHandlers : {})}
      style={interactive ? [{ opacity }, motion] : undefined}
      className="w-full gap-3 rounded-[22px] border border-border bg-surface p-4"
    >
      <View className="w-[70%] self-center">
        <PosterCard
          fill
          title={movie.title}
          posterUrl={movie.posterUrl}
          certification={result.certification}
          subtitle={movie.releaseYear ? String(movie.releaseYear) : undefined}
        />
        {interactive ? (
          <>
            <Animated.View
              testID="swipe-stamp-yes"
              pointerEvents="none"
              style={{ opacity: yesOpacity }}
              className="absolute left-3 top-6 -rotate-12 rounded-lg border-4 border-accent px-3 py-1"
            >
              <Text className="font-display-bold text-2xl uppercase tracking-widest text-accent">Yes</Text>
            </Animated.View>
            <Animated.View
              testID="swipe-stamp-nope"
              pointerEvents="none"
              style={{ opacity: nopeOpacity }}
              className="absolute right-3 top-6 rotate-12 rounded-lg border-4 border-danger px-3 py-1"
            >
              <Text className="font-display-bold text-2xl uppercase tracking-widest text-danger">Nope</Text>
            </Animated.View>
          </>
        ) : null}
      </View>
      <FuseMeter members={members} scores={result.memberScores} />
      {result.explanation ? (
        <Body muted className="text-sm">
          {result.explanation}
        </Body>
      ) : null}
      <View className="flex-row items-center justify-between gap-2">
        <Text className="font-body-semibold text-xs text-muted">{progress}</Text>
        <Pressable
          accessibilityRole="link"
          accessibilityLabel={`Details for ${movie.title}`}
          disabled={!interactive}
          onPress={onOpenMovie}
          className="min-h-11 justify-center px-2 active:opacity-80"
        >
          <Text className="font-body-bold text-sm text-accent-ink">Details</Text>
        </Pressable>
      </View>
    </Animated.View>
  );
}
