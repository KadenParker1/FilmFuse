import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useDismissPendingRating, usePendingRatings } from '../../api/pendingRatings';
import { useTheme } from '../../theme/useTheme';
import type { PendingRatingDTO } from '../../types/api';
import { Body, Button, Card, Heading, errorMessage } from '../ui';
import { useQuickRate } from './QuickRateProvider';

/** How many prompts show before "Show N more". */
export const PENDING_PREVIEW = 3;

export const watchedWith = (p: Pick<PendingRatingDTO, 'groupName'>) =>
  p.groupName ? `Watched with ${p.groupName}` : 'Watched on a movie night';

function PendingRow({ pending, onRate, onDismiss }: { pending: PendingRatingDTO; onRate: () => void; onDismiss: () => void }) {
  const { colors } = useTheme();
  const { movie } = pending;
  return (
    <View testID={`pending-rating-${movie.id}`} className="flex-row items-center gap-3">
      <View className="h-16 w-11 overflow-hidden rounded-lg bg-raised">
        {movie.posterUrl ? (
          <Image
            testID={`pending-poster-${movie.id}`}
            source={{ uri: movie.posterUrl }}
            style={{ width: '100%', height: '100%' }}
            contentFit="cover"
          />
        ) : (
          <View className="flex-1 items-center justify-center">
            <Ionicons name="film-outline" size={16} color={colors.muted} />
          </View>
        )}
      </View>
      <View className="flex-1 gap-0.5">
        <Text className="font-body-semibold text-base text-text" numberOfLines={1}>
          {movie.title}
        </Text>
        <Text className="font-body text-sm text-muted" numberOfLines={1}>
          {watchedWith(pending)}
        </Text>
      </View>
      <Button size="sm" title="Rate" accessibilityLabel={`Rate ${movie.title}`} onPress={onRate} />
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Dismiss ${movie.title}`}
        accessibilityHint="You won't be asked to rate it again"
        hitSlop={8}
        onPress={onDismiss}
        className="h-9 w-9 items-center justify-center rounded-full active:opacity-70"
      >
        <Ionicons name="close" size={18} color={colors.muted} />
      </Pressable>
    </View>
  );
}

/**
 * "Rate what you watched": recent session picks you haven't rated yet. Rate opens the quick-rate
 * sheet (as a post-watch rating); ✕ dismisses for good. Renders nothing while loading, on error
 * or when there's nothing to rate — it's a nudge, never a blocker.
 */
export function PendingRatingsBanner() {
  const { colors } = useTheme();
  const pending = usePendingRatings();
  const dismiss = useDismissPendingRating();
  const { openQuickRate } = useQuickRate();
  const [expanded, setExpanded] = useState(false);

  const items = pending.data ?? [];
  if (items.length === 0) return null;
  const shown = expanded ? items : items.slice(0, PENDING_PREVIEW);
  const hidden = items.length - shown.length;

  return (
    <Card testID="pending-ratings" className="gap-3 border-accent">
      <View className="flex-row items-center gap-2">
        <Ionicons name="star" size={18} color={colors.accent} />
        <Heading size="sm" className="flex-1">
          Rate what you watched
        </Heading>
      </View>
      <Body muted className="text-sm">
        A quick 1–10 sharpens everyone&apos;s next picks.
      </Body>
      {dismiss.isError ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {errorMessage(dismiss.error)}
        </Body>
      ) : null}
      {shown.map((p) => (
        <PendingRow
          key={p.movie.id}
          pending={p}
          onRate={() => openQuickRate(p.movie, { source: 'POST_WATCH' })}
          onDismiss={() => dismiss.mutate(p.movie.id)}
        />
      ))}
      {hidden > 0 ? (
        <Button size="sm" variant="ghost" title={`Show ${hidden} more`} onPress={() => setExpanded(true)} />
      ) : null}
    </Card>
  );
}
