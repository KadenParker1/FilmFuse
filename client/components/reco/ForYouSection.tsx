import { useMemo } from 'react';
import { ScrollView, View } from 'react-native';
import { useRouter } from 'expo-router';
import { flattenPages } from '../../api/movies';
import { useDismissRecommendation, useRecommendations } from '../../api/recommendations';
import { useTasteProfile } from '../../api/taste';
import { useQuickRate } from '../rating/QuickRateProvider';
import { Body, Button, Card, ErrorState, Heading, Skeleton, errorMessage } from '../ui';
import { RecommendationCard } from './RecommendationCard';

function ForYouSkeleton() {
  return (
    <View testID="for-you-loading" className="flex-row gap-4">
      {[0, 1, 2].map((i) => (
        <View key={i} className="w-40 gap-2">
          <Skeleton className="aspect-[2/3] w-full" />
          <Skeleton className="h-4 w-3/4" />
        </View>
      ))}
    </View>
  );
}

/** Discover: personal picks from the caller's taste vector, with Seen it (quick rate) / Not interested. */
export function ForYouSection() {
  const router = useRouter();
  const { openQuickRate, openRatePicker } = useQuickRate();
  const taste = useTasteProfile();
  const recs = useRecommendations();
  const dismiss = useDismissRecommendation();
  const items = useMemo(() => flattenPages(recs.data), [recs.data]);
  const noVector = taste.data?.status === 'NONE';

  let body;
  if (noVector) {
    body = (
      <Card testID="for-you-no-vector" className="items-center gap-3">
        <Heading size="sm" className="text-center">
          Rate a few movies to get picks
        </Heading>
        <Body muted className="text-center">
          Your picks come from your taste. Score a few things you’ve watched and they’ll show up here.
        </Body>
        <Button title="Rate a movie" onPress={openRatePicker} />
      </Card>
    );
  } else if (recs.isPending) {
    body = <ForYouSkeleton />;
  } else if (recs.isError && items.length === 0) {
    body = (
      <ErrorState
        compact
        title="Couldn't load your picks"
        error={recs.error}
        onRetry={() => void recs.refetch()}
        retrying={recs.isFetching}
      />
    );
  } else if (items.length === 0) {
    body = (
      <Body muted testID="for-you-empty">
        No new picks right now. Rate more movies and we’ll find more.
      </Body>
    );
  } else {
    body = (
      <ScrollView
        horizontal
        testID="for-you-list"
        showsHorizontalScrollIndicator={false}
        contentContainerClassName="gap-4 pr-4"
      >
        {items.map((rec) => (
          <RecommendationCard
            key={rec.movie.id}
            rec={rec}
            onOpen={() => router.push(`/movie/${rec.movie.id}`)}
            onSeen={() => openQuickRate(rec.movie)}
            onDismiss={() => dismiss.mutate(rec.movie.id)}
          />
        ))}
        {recs.hasNextPage ? (
          <View className="w-32 justify-center">
            <Button
              title="More picks"
              variant="ghost"
              loading={recs.isFetchingNextPage}
              onPress={() => void recs.fetchNextPage()}
            />
          </View>
        ) : null}
      </ScrollView>
    );
  }

  return (
    <View testID="for-you" className="gap-3 pb-2">
      <Heading size="sm">For you</Heading>
      {body}
      {dismiss.isError ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          Couldn’t hide that pick. {errorMessage(dismiss.error)}
        </Body>
      ) : null}
    </View>
  );
}
