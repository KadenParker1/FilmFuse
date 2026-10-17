import { useMemo } from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { flattenPages } from '../../api/movies';
import { useMyRatings } from '../../api/ratings';
import { Body, Button, ErrorState, Heading, Skeleton } from '../ui';
import { RatingRow } from './RatingRow';

/**
 * Profile tab: "Your ratings", newest first. It lives inside the profile's ScrollView, so it isn't
 * virtualised; further pages load with "Show more".
 */
export function RatingsList() {
  const router = useRouter();
  const ratings = useMyRatings();
  const items = useMemo(() => flattenPages(ratings.data), [ratings.data]);

  let body;
  if (ratings.isPending) {
    body = (
      <View testID="ratings-loading" className="gap-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-20 w-full" />
        ))}
      </View>
    );
  } else if (ratings.isError && items.length === 0) {
    body = (
      <ErrorState
        compact
        title="Couldn't load your ratings"
        error={ratings.error}
        onRetry={() => void ratings.refetch()}
        retrying={ratings.isFetching}
      />
    );
  } else if (items.length === 0) {
    body = (
      <Body muted testID="ratings-empty">
        Nothing rated yet. Tap “+ Rate” after you watch something.
      </Body>
    );
  } else {
    body = (
      <View testID="ratings-list">
        {items.map((r) => (
          <RatingRow key={r.id} rating={r} onPress={() => router.push(`/movie/${r.movie.id}`)} />
        ))}
        {ratings.isError ? (
          <ErrorState
            compact
            title="Couldn't load more"
            error={ratings.error}
            onRetry={() => void ratings.fetchNextPage()}
            retrying={ratings.isFetchingNextPage}
          />
        ) : ratings.hasNextPage ? (
          <Button
            title="Show more"
            variant="ghost"
            loading={ratings.isFetchingNextPage}
            onPress={() => void ratings.fetchNextPage()}
          />
        ) : null}
      </View>
    );
  }

  return (
    <View className="gap-3">
      <Heading size="sm">Your ratings</Heading>
      {body}
    </View>
  );
}
