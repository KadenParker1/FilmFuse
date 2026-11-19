import { useEffect, useState } from 'react';
import { Animated, Platform, Text, View } from 'react-native';
import type { SessionResultDTO } from '../../types/api';
import { Body, Button, PosterCard } from '../ui';

type Props = {
  result: SessionResultDTO;
  /** No pop-in motion. */
  reduceMotion: boolean;
  /** Opens the usual "Watch X?" pick confirmation. */
  onPick: () => void;
  onKeepSwiping: () => void;
};

/** "Everyone's in!": every member swiped right on this movie. Pick it now or keep swiping. */
export function MatchCard({ result, reduceMotion, onPick, onKeepSwiping }: Props) {
  const { movie } = result;
  const [scale] = useState(() => new Animated.Value(reduceMotion ? 1 : 0.85));

  useEffect(() => {
    if (reduceMotion) {
      scale.setValue(1);
      return;
    }
    const anim = Animated.spring(scale, { toValue: 1, friction: 5, tension: 120, useNativeDriver: Platform.OS !== 'web' });
    anim.start();
    return () => anim.stop();
  }, [reduceMotion, scale]);

  return (
    <Animated.View
      testID="match-card"
      style={reduceMotion ? undefined : { transform: [{ scale }] }}
      className="w-full items-center gap-4 rounded-[22px] border-2 border-accent2 bg-surface p-5"
    >
      <Text accessibilityRole="header" className="text-center font-display-bold text-2xl tracking-tight text-text">
        Everyone’s in! 🎉
      </Text>
      <View className="w-1/2">
        <PosterCard fill title={movie.title} posterUrl={movie.posterUrl} certification={result.certification} />
      </View>
      <Body muted className="text-center text-sm">
        The whole group swiped right on {movie.title}.
      </Body>
      <View className="w-full gap-2">
        <Button title="Pick this" accessibilityLabel={`Pick ${movie.title}`} onPress={onPick} />
        <Button title="Keep swiping" variant="ghost" onPress={onKeepSwiping} />
      </View>
    </Animated.View>
  );
}
