import { Pressable, Text, View } from 'react-native';
import { Image } from 'expo-image';
import type { Certification } from '../../types/api';

type Props = {
  title: string;
  posterUrl?: string | null;
  /** 1–10; shown as an accent badge (top right) when present. */
  score?: number | null;
  /** Shown as a small chip on the artwork (top left); undefined = no chip, null = "NR". */
  certification?: Certification | null;
  subtitle?: string;
  onPress?: () => void;
  /** Fill the parent's width (grid cells) instead of the fixed rail width. */
  fill?: boolean;
  className?: string;
};

/** Poster-first card (2:3 artwork) with title and an optional score badge. */
export function PosterCard({ title, posterUrl, score, certification, subtitle, onPress, fill = false, className = '' }: Props) {
  return (
    <Pressable
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityLabel={score != null ? `${title}, scored ${score} out of 10` : title}
      disabled={!onPress}
      onPress={onPress}
      className={`${fill ? 'w-full' : 'w-36'} gap-2 active:opacity-80 ${className}`}
    >
      <View className="aspect-[2/3] w-full overflow-hidden rounded-[14px] bg-raised">
        <View className="absolute inset-0 items-center justify-center p-2">
          <Text className="text-center font-display-bold text-base text-muted" numberOfLines={3}>
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
        {certification !== undefined ? (
          // Always dark-on-light regardless of scheme: it sits on the poster artwork, not on a surface.
          <View
            testID="poster-certification"
            accessibilityLabel={certification ? `Rated ${certification.replace('_', '-')}` : 'Not rated'}
            className="absolute left-2 top-2 rounded-md px-1.5 py-0.5"
            style={{ backgroundColor: 'rgba(0,0,0,0.6)' }}
          >
            <Text className="font-body-bold text-[10px] tracking-wide" style={{ color: '#FFFFFF' }}>
              {certification ? certification.replace('_', '-') : 'NR'}
            </Text>
          </View>
        ) : null}
        {score != null ? (
          <View testID="poster-score" className="absolute right-2 top-2 rounded-full bg-accent px-2 py-0.5">
            <Text className="font-body-bold text-sm text-on-accent">{score}</Text>
          </View>
        ) : null}
      </View>
      <Text className="font-body-bold text-sm leading-5 text-text" numberOfLines={2}>
        {title}
      </Text>
      {subtitle ? (
        <Text className="font-body text-xs text-muted" numberOfLines={1}>
          {subtitle}
        </Text>
      ) : null}
    </Pressable>
  );
}

/** The title placeholder sits underneath, so a missing or slow poster still reads as the movie. */
const StyleSheetFill = { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 } as const;
