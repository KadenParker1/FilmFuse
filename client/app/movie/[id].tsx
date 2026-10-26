import { Pressable, ScrollView, Text, View } from 'react-native';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { toMovieSummary, useMovie } from '../../api/movies';
import { useMyRating } from '../../api/ratings';
import { MovieRatingsSection } from '../../components/feed/RatingsSections';
import { useQuickRate } from '../../components/rating/QuickRateProvider';
import { TmdbAttribution } from '../../components/TmdbAttribution';
import { Body, Button, EmptyState, ErrorState, Heading, Label, Skeleton } from '../../components/ui';
import { ApiError } from '../../lib/apiClient';
import { formatRuntime } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { MovieDetailDTO } from '../../types/api';

function metaLine(movie: MovieDetailDTO): string {
  return [movie.releaseYear, formatRuntime(movie.runtime), movie.genres.map((g) => g.name).join(', ')]
    .filter((p) => p != null && p !== '')
    .join(' · ');
}

function BackButton() {
  const router = useRouter();
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel="Back"
      hitSlop={8}
      // A deep link or web refresh has no history to pop.
      onPress={() => (router.canGoBack() ? router.back() : router.replace('/discover'))}
      className="h-10 w-10 items-center justify-center rounded-xl bg-bg/80 active:opacity-70"
    >
      <Ionicons name="chevron-back" size={22} color={colors.text} />
    </Pressable>
  );
}

function DetailSkeleton() {
  return (
    <View testID="movie-skeleton" className="gap-4">
      <Skeleton className="h-56 w-full rounded-none" />
      <View className="flex-row gap-4 px-5">
        <Skeleton className="-mt-20 h-48 w-32" />
        <View className="flex-1 gap-2 pt-2">
          <Skeleton className="h-7 w-3/4" />
          <Skeleton className="h-4 w-1/2" />
        </View>
      </View>
      <View className="gap-2 px-5">
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-2/3" />
      </View>
    </View>
  );
}

function MovieDetail({ movie }: { movie: MovieDetailDTO }) {
  const { colors } = useTheme();
  const router = useRouter();
  const { openQuickRate } = useQuickRate();
  const myRating = useMyRating(movie.id);
  const meta = metaLine(movie);
  return (
    <View className="w-full max-w-4xl self-center">
      <View className="h-56 w-full overflow-hidden bg-raised md:h-80">
        {movie.backdropUrl ? (
          <Image
            testID="movie-backdrop"
            source={{ uri: movie.backdropUrl }}
            style={{ width: '100%', height: '100%' }}
            contentFit="cover"
            transition={250}
          />
        ) : null}
        <View className="absolute inset-x-0 bottom-0 h-24 bg-bg/60" />
      </View>

      <View className="flex-row gap-4 px-5">
        <View className="-mt-20 h-48 w-32 overflow-hidden rounded-2xl bg-raised">
          {movie.posterUrl ? (
            <Image
              testID="movie-poster"
              source={{ uri: movie.posterUrl }}
              accessibilityLabel={`${movie.title} poster`}
              style={{ width: '100%', height: '100%' }}
              contentFit="cover"
              transition={200}
            />
          ) : (
            <View className="flex-1 items-center justify-center p-2">
              <Ionicons name="film-outline" size={32} color={colors.muted} />
            </View>
          )}
        </View>
        <View className="flex-1 gap-1.5 pt-3">
          <Heading size="md">{movie.title}</Heading>
          {meta ? <Body muted className="text-sm">{meta}</Body> : null}
          {movie.voteAverage != null ? (
            <View
              className="mt-1 flex-row items-center gap-1 self-start rounded-full bg-accent px-2.5 py-0.5"
              accessibilityLabel={`TMDB rating ${movie.voteAverage.toFixed(1)} out of 10`}
            >
              <Ionicons name="star" size={12} color={colors.onAccent} />
              <Text className="font-body-semibold text-sm text-on-accent">{movie.voteAverage.toFixed(1)}</Text>
            </View>
          ) : null}
        </View>
      </View>

      <View className="gap-5 px-5 pt-6">
        <View className="flex-row gap-3">
          <Button
            title={myRating.data ? 'Edit rating' : 'Rate'}
            className="flex-1"
            onPress={() => openQuickRate(toMovieSummary(movie))}
            icon={<Ionicons name={myRating.data ? 'star' : 'star-outline'} size={18} color={colors.onAccent} />}
          />
          <Button
            title="Add to watchlist"
            variant="secondary"
            disabled
            className="flex-1"
            accessibilityHint="Coming soon"
            icon={<Ionicons name="bookmark-outline" size={18} color={colors.text} />}
          />
        </View>
        <Button
          title="Watch with…"
          variant="secondary"
          accessibilityLabel={`Watch ${movie.title} with friends`}
          accessibilityHint="Starts a movie night"
          icon={<Ionicons name="people-outline" size={18} color={colors.text} />}
          onPress={() => router.push(`/sessions/new?movieId=${movie.id}`)}
        />
        {myRating.data ? (
          <View testID="my-rating" className="-mt-2 flex-row items-center gap-2 self-center">
            <Ionicons name="star" size={14} color={colors.accent} />
            <Text className="font-body-semibold text-sm text-text">Your rating {myRating.data.score}/10</Text>
          </View>
        ) : (
          <Label className="-mt-3 text-center text-xs">Watchlist is coming soon</Label>
        )}
        <View className="gap-2">
          <Heading size="sm">Overview</Heading>
          <Body muted={!movie.overview}>{movie.overview || 'No overview available yet.'}</Body>
        </View>
        <MovieRatingsSection movieId={movie.id} title={movie.title} />
        <TmdbAttribution />
      </View>
    </View>
  );
}

export default function MovieScreen() {
  const insets = useSafeAreaInsets();
  const params = useLocalSearchParams<{ id: string }>();
  const id = Number(Array.isArray(params.id) ? params.id[0] : params.id);
  const valid = Number.isInteger(id) && id > 0;
  const movie = useMovie(id);
  const notFound = !valid || (movie.error instanceof ApiError && movie.error.status === 404);

  let content;
  if (notFound) {
    content = <EmptyState icon="help-circle-outline" title="Movie not found" message="We couldn't find that movie." />;
  } else if (movie.isError) {
    content = (
      <ErrorState
        title="Couldn't load this movie"
        error={movie.error}
        onRetry={() => void movie.refetch()}
        retrying={movie.isFetching}
      />
    );
  } else if (movie.isPending) {
    content = <DetailSkeleton />;
  } else {
    content = <MovieDetail movie={movie.data} />;
  }

  return (
    <View className="flex-1 bg-bg">
      <Stack.Screen options={{ title: movie.data?.title ?? 'Movie' }} />
      <ScrollView contentContainerClassName="flex-grow" contentContainerStyle={{ paddingBottom: insets.bottom + 24 }}>
        {content}
      </ScrollView>
      <View className="absolute" style={{ top: insets.top + 8, left: insets.left + 16 }}>
        <BackButton />
      </View>
    </View>
  );
}
