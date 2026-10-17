import { Pressable, Text, View } from 'react-native';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../../theme/useTheme';
import type { RatingDTO, RatingVisibility } from '../../types/api';
import { scoreLabel } from '../ui';

/** Icon and words for who can see a rating. */
export const VISIBILITY: Record<RatingVisibility, { icon: 'lock-closed-outline' | 'people-outline' | 'globe-outline'; label: string }> = {
  PRIVATE: { icon: 'lock-closed-outline', label: 'Only you' },
  FRIENDS: { icon: 'people-outline', label: 'Friends' },
  PUBLIC: { icon: 'globe-outline', label: 'Public' },
};

/** One of the caller's ratings: poster, title, score and (spoiler-hidden) note. */
export function RatingRow({ rating, onPress }: { rating: RatingDTO; onPress: () => void }) {
  const { colors } = useTheme();
  const { movie } = rating;
  const vis = VISIBILITY[rating.visibility];
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${movie.title}, you rated it ${rating.score} out of 10`}
      onPress={onPress}
      className="flex-row gap-3 py-2 active:opacity-70"
    >
      <View className="h-20 w-14 overflow-hidden rounded-md border border-border bg-surface-raised">
        {movie.posterUrl ? (
          <Image source={{ uri: movie.posterUrl }} style={{ width: '100%', height: '100%' }} contentFit="cover" />
        ) : null}
      </View>
      <View className="flex-1 gap-1">
        <View className="flex-row items-center gap-2">
          <Text className="flex-1 font-body-semibold text-base text-text" numberOfLines={1}>
            {movie.title}
          </Text>
          <View className="rounded-full bg-gold px-2 py-0.5">
            <Text className="font-body-semibold text-sm text-on-gold">{rating.score}/10</Text>
          </View>
        </View>
        <Text className="font-body text-sm text-text-muted">
          {scoreLabel(rating.score)}
          {movie.releaseYear ? ` · ${movie.releaseYear}` : ''}
        </Text>
        {rating.body ? (
          <Text testID={`rating-note-${rating.id}`} className="font-body text-sm text-text" numberOfLines={2}>
            {rating.containsSpoilers ? 'Note hidden: contains spoilers' : rating.body}
          </Text>
        ) : null}
        <View className="flex-row items-center gap-1">
          <Ionicons name={vis.icon} size={12} color={colors.textMuted} />
          <Text className="font-body text-xs text-text-muted">{vis.label}</Text>
        </View>
      </View>
    </Pressable>
  );
}
