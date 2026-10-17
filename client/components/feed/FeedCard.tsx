import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useLikeRating, useUnlikeRating } from '../../api/feed';
import { personName, pluralize, timeAgo } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { FeedItemDTO } from '../../types/api';
import { useQuickRate } from '../rating/QuickRateProvider';
import { VISIBILITY } from '../rating/RatingRow';
import { CertificationBadge } from '../sessions/CertificationBadge';
import { Avatar, Skeleton, scoreLabel } from '../ui';

type Props = {
  item: FeedItemDTO;
  /** Hide the author header (e.g. on that person's own ratings list). */
  showAuthor?: boolean;
  /** Hide the poster / title row (e.g. on the movie's own page). */
  showMovie?: boolean;
};

/** A note marked as containing spoilers stays covered until tapped. */
function Note({ item }: { item: FeedItemDTO }) {
  const { colors } = useTheme();
  const [revealed, setRevealed] = useState(false);
  if (!item.body) return null;
  if (item.containsSpoilers && !revealed) {
    return (
      <Pressable
        testID={`feed-spoiler-${item.id}`}
        accessibilityRole="button"
        accessibilityLabel="Note hidden: contains spoilers"
        accessibilityHint="Reveals the note"
        onPress={() => setRevealed(true)}
        className="gap-2 overflow-hidden rounded-xl border border-border bg-surface-raised p-3 active:opacity-80"
      >
        {/* Stand-in "blurred" lines: the hidden text is never rendered, so it can't be read out. */}
        <View
          importantForAccessibility="no-hide-descendants"
          accessibilityElementsHidden
          className="gap-1.5 opacity-60"
        >
          <View className="h-2.5 w-full rounded-full bg-border" />
          <View className="h-2.5 w-4/5 rounded-full bg-border" />
        </View>
        <View className="flex-row items-center gap-1.5">
          <Ionicons name="eye-off-outline" size={14} color={colors.textMuted} />
          <Text className="font-body-medium text-sm text-text-muted">Contains spoilers · Tap to reveal</Text>
        </View>
      </Pressable>
    );
  }
  return (
    <Text testID={`feed-note-${item.id}`} className="font-body text-base leading-6 text-text">
      {item.body}
    </Text>
  );
}

function LikeButton({ item }: { item: FeedItemDTO }) {
  const { colors } = useTheme();
  const like = useLikeRating();
  const unlike = useUnlikeRating();
  const liked = item.likedByMe;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${liked ? 'Unlike' : 'Like'} rating, ${pluralize(item.likeCount, 'like')}`}
      accessibilityState={{ selected: liked }}
      hitSlop={8}
      onPress={() => (liked ? unlike.mutate(item.id) : like.mutate(item.id))}
      className="min-h-9 flex-row items-center gap-1.5 rounded-full px-2 active:opacity-70"
    >
      <Ionicons
        name={liked ? 'heart' : 'heart-outline'}
        size={20}
        color={liked ? colors.velvet : colors.textMuted}
        testID={liked ? 'icon-heart-filled' : 'icon-heart-outline'}
      />
      <Text testID={`feed-like-count-${item.id}`} className="font-body-medium text-sm text-text-muted">
        {item.likeCount}
      </Text>
    </Pressable>
  );
}

/**
 * One rating in a feed: who, when, the movie, the score, an optional (spoiler-covered) note and a
 * like. Your own ratings also show who can see them and an "Edit" that reopens the quick-rate sheet.
 * Without a note the card is compact.
 */
export function FeedCard({ item, showAuthor = true, showMovie = true }: Props) {
  const router = useRouter();
  const { colors } = useTheme();
  const { openQuickRate } = useQuickRate();
  const { author, movie } = item;
  const name = item.mine ? 'You' : personName(author);
  const when = timeAgo(item.updatedAt);
  const compact = !item.body;
  const vis = VISIBILITY[item.visibility];
  const label = scoreLabel(item.score);

  return (
    <View
      testID={`feed-card-${item.id}`}
      className={`rounded-2xl border border-border bg-surface ${compact ? 'gap-2 p-3' : 'gap-3 p-4'}`}
    >
      {showAuthor ? (
        <View className="flex-row items-center gap-2">
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`${name}${when ? `, ${when}` : ''}`}
            accessibilityHint="Opens their profile"
            onPress={() => router.push(`/user/${author.id}`)}
            className="flex-1 flex-row items-center gap-2 active:opacity-70"
          >
            <Avatar
              uri={author.profileImageUrl}
              displayName={author.displayName}
              username={author.username}
              size={compact ? 28 : 36}
            />
            <View className="flex-1">
              <Text className="font-body-semibold text-sm text-text" numberOfLines={1}>
                {name}
              </Text>
              {when ? <Text className="font-body text-xs text-text-muted">{when}</Text> : null}
            </View>
          </Pressable>
        </View>
      ) : null}

      <View className="flex-row items-center gap-3">
        {showMovie ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`${movie.title}${movie.releaseYear ? `, ${movie.releaseYear}` : ''}`}
            accessibilityHint="Opens the movie"
            onPress={() => router.push(`/movie/${movie.id}`)}
            className="flex-1 flex-row items-center gap-3 active:opacity-70"
          >
            <View
              testID={compact ? 'feed-poster-compact' : 'feed-poster'}
              className={`overflow-hidden rounded-md border border-border bg-surface-raised ${
                compact ? 'h-14 w-10' : 'h-24 w-16'
              }`}
            >
              {movie.posterUrl ? (
                <Image source={{ uri: movie.posterUrl }} style={{ width: '100%', height: '100%' }} contentFit="cover" />
              ) : (
                <View className="flex-1 items-center justify-center">
                  <Ionicons name="film-outline" size={18} color={colors.textMuted} />
                </View>
              )}
            </View>
            <View className="flex-1 gap-1">
              <Text className="font-body-semibold text-base text-text" numberOfLines={2}>
                {movie.title}
              </Text>
              <View className="flex-row items-center gap-2">
                {movie.releaseYear ? (
                  <Text className="font-body text-sm text-text-muted">{movie.releaseYear}</Text>
                ) : null}
                <CertificationBadge certification={item.certification} />
              </View>
            </View>
          </Pressable>
        ) : (
          <View className="flex-1" />
        )}
        <View
          testID={`feed-score-${item.id}`}
          accessible
          accessibilityLabel={`Rated ${item.score} out of 10, ${label}`}
          className="items-end gap-0.5"
        >
          <View className={`rounded-full bg-gold ${compact ? 'px-2.5 py-0.5' : 'px-3 py-1'}`}>
            <Text className={`font-display-bold text-on-gold ${compact ? 'text-base' : 'text-xl'}`}>
              {item.score}/10
            </Text>
          </View>
          <Text className="font-body text-xs text-text-muted">{label}</Text>
        </View>
      </View>

      <Note item={item} />

      <View className="flex-row items-center justify-between">
        <View className="flex-row items-center gap-2">
          <LikeButton item={item} />
          {!showAuthor && when ? <Text className="font-body text-xs text-text-muted">{when}</Text> : null}
        </View>
        {item.mine ? (
          <View className="flex-row items-center gap-3">
            <View
              testID={`feed-visibility-${item.id}`}
              accessible
              accessibilityLabel={`Visible to: ${vis.label}`}
              className="flex-row items-center gap-1"
            >
              <Ionicons name={vis.icon} size={14} color={colors.textMuted} />
              <Text className="font-body text-xs text-text-muted">{vis.label}</Text>
            </View>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`Edit your rating of ${movie.title}`}
              hitSlop={8}
              onPress={() => openQuickRate(movie)}
              className="min-h-9 flex-row items-center gap-1 rounded-full px-2 active:opacity-70"
            >
              <Ionicons name="create-outline" size={16} color={colors.gold} />
              <Text className="font-body-semibold text-sm text-gold">Edit</Text>
            </Pressable>
          </View>
        ) : null}
      </View>
    </View>
  );
}

/** Placeholder cards while a feed loads. */
export function FeedCardSkeletons({ count = 3, testID }: { count?: number; testID?: string }) {
  return (
    <View testID={testID} className="gap-3">
      {Array.from({ length: count }, (_, i) => (
        <View key={i} className="gap-3 rounded-2xl border border-border bg-surface p-4">
          <View className="flex-row items-center gap-2">
            <Skeleton className="h-9 w-9 rounded-full" />
            <Skeleton className="h-4 w-32" />
          </View>
          <View className="flex-row gap-3">
            <Skeleton className="h-24 w-16" />
            <View className="flex-1 gap-2">
              <Skeleton className="h-5 w-3/4" />
              <Skeleton className="h-4 w-1/3" />
            </View>
          </View>
        </View>
      ))}
    </View>
  );
}
