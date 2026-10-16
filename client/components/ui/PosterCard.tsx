import { Pressable, Text, View } from 'react-native';
import { Image } from 'expo-image';

type Props = {
  title: string;
  posterUrl?: string | null;
  /** 1–10; shown as a gold badge when present. */
  score?: number | null;
  subtitle?: string;
  onPress?: () => void;
  /** Fill the parent's width (grid cells) instead of the fixed rail width. */
  fill?: boolean;
  className?: string;
};

/** Poster-first card (2:3 artwork) with title and an optional score badge. */
export function PosterCard({ title, posterUrl, score, subtitle, onPress, fill = false, className = '' }: Props) {
  return (
    <Pressable
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityLabel={score != null ? `${title}, scored ${score} out of 10` : title}
      disabled={!onPress}
      onPress={onPress}
      className={`${fill ? 'w-full' : 'w-36'} gap-2 active:opacity-80 ${className}`}
    >
      <View className="aspect-[2/3] w-full overflow-hidden rounded-xl border border-border bg-surface-raised">
        <View className="absolute inset-0 items-center justify-center p-2">
          <Text className="text-center font-display text-base text-text-muted" numberOfLines={3}>
            {title}
          </Text>
        </View>
        {posterUrl ? (
          <Image
            testID="poster-image"
            source={{ uri: posterUrl }}
            accessibilityIgnoresInvertColors
            style={StyleSheetFill}
            contentFit="cover"
            transition={200}
            recyclingKey={posterUrl}
          />
        ) : null}
        {score != null ? (
          <View testID="poster-score" className="absolute right-2 top-2 rounded-full bg-gold px-2 py-0.5">
            <Text className="font-body-semibold text-sm text-on-gold">{score}</Text>
          </View>
        ) : null}
      </View>
      <Text className="font-body-semibold text-sm text-text" numberOfLines={2}>
        {title}
      </Text>
      {subtitle ? (
        <Text className="font-body text-xs text-text-muted" numberOfLines={1}>
          {subtitle}
        </Text>
      ) : null}
    </Pressable>
  );
}

/** The title placeholder sits underneath, so a missing or slow poster still reads as the movie. */
const StyleSheetFill = { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 } as const;
