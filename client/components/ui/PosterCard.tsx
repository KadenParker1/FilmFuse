import { Image, Pressable, Text, View } from 'react-native';

type Props = {
  title: string;
  posterUrl?: string | null;
  /** 1–10; shown as a gold badge when present. */
  score?: number | null;
  subtitle?: string;
  onPress?: () => void;
  className?: string;
};

/** Poster-first card (2:3 artwork) with title and an optional score badge. */
export function PosterCard({ title, posterUrl, score, subtitle, onPress, className = '' }: Props) {
  return (
    <Pressable
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityLabel={score != null ? `${title}, scored ${score} out of 10` : title}
      disabled={!onPress}
      onPress={onPress}
      className={`w-36 gap-2 active:opacity-80 ${className}`}
    >
      <View className="aspect-[2/3] w-full overflow-hidden rounded-xl border border-border bg-surface-raised">
        {posterUrl ? (
          <Image source={{ uri: posterUrl }} accessibilityIgnoresInvertColors className="h-full w-full" resizeMode="cover" />
        ) : (
          <View className="flex-1 items-center justify-center p-2">
            <Text className="text-center font-display text-base text-text-muted" numberOfLines={3}>
              {title}
            </Text>
          </View>
        )}
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
