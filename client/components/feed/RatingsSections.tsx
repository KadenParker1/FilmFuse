import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';
import type { InfiniteData, UseInfiniteQueryResult } from '@tanstack/react-query';
import { useMovieRatings, useUserRatings } from '../../api/feed';
import { flattenPages } from '../../api/movies';
import type { FeedItemDTO, FeedScope, PageDTO } from '../../types/api';
import { Body, Button, ChoiceGroup, ErrorState, Heading } from '../ui';
import { FeedCard, FeedCardSkeletons } from './FeedCard';
import { SCOPE_CHOICES } from './FeedPanel';

/** How many ratings the movie page previews before "See all". */
export const MOVIE_RATINGS_PREVIEW = 3;
/** How many ratings a profile previews before "See all". */
export const USER_RATINGS_PREVIEW = 5;

type PreviewQuery = Pick<
  UseInfiniteQueryResult<InfiniteData<PageDTO<FeedItemDTO>>>,
  'data' | 'isPending' | 'isError' | 'error' | 'isFetching' | 'hasNextPage' | 'refetch'
>;

type PreviewProps = {
  query: PreviewQuery;
  limit: number;
  emptyText: string;
  onSeeAll: () => void;
  seeAllLabel: string;
  showAuthor?: boolean;
  showMovie?: boolean;
  testID: string;
};

/** The first few cards of a ratings list (not virtualised: it lives inside a page's ScrollView). */
function RatingsPreview({
  query,
  limit,
  emptyText,
  onSeeAll,
  seeAllLabel,
  showAuthor,
  showMovie,
  testID,
}: PreviewProps) {
  const items = useMemo(() => flattenPages(query.data), [query.data]);
  if (query.isPending) return <FeedCardSkeletons count={2} testID={`${testID}-loading`} />;
  if (query.isError && items.length === 0) {
    return (
      <ErrorState
        compact
        title="Couldn't load ratings"
        error={query.error}
        onRetry={() => void query.refetch()}
        retrying={query.isFetching}
      />
    );
  }
  if (items.length === 0) {
    return (
      <Body muted testID={`${testID}-empty`}>
        {emptyText}
      </Body>
    );
  }
  const more = items.length > limit || !!query.hasNextPage;
  return (
    <View testID={`${testID}-list`} className="gap-3">
      {items.slice(0, limit).map((item) => (
        <FeedCard key={item.id} item={item} showAuthor={showAuthor} showMovie={showMovie} />
      ))}
      {more ? <Button title="See all" accessibilityLabel={seeAllLabel} variant="ghost" onPress={onSeeAll} /> : null}
    </View>
  );
}

/** Movie detail: what friends (or everyone) thought, with a link to the full list. */
export function MovieRatingsSection({ movieId, title }: { movieId: number; title: string }) {
  const router = useRouter();
  const [scope, setScope] = useState<FeedScope>('FRIENDS');
  const ratings = useMovieRatings(movieId, scope);
  return (
    <View testID="movie-ratings" className="gap-3">
      <Heading size="sm">Ratings</Heading>
      <ChoiceGroup label="Whose ratings" choices={SCOPE_CHOICES} value={scope} onChange={setScope} />
      <RatingsPreview
        key={scope}
        testID="movie-ratings"
        query={ratings}
        limit={MOVIE_RATINGS_PREVIEW}
        showMovie={false}
        emptyText={
          scope === 'FRIENDS' ? 'None of your friends have rated this yet.' : 'No public ratings of this movie yet.'
        }
        seeAllLabel={`See all ratings of ${title}`}
        onSeeAll={() => router.push(`/movie/${movieId}/ratings?scope=${scope}`)}
      />
    </View>
  );
}

/** Public profile: that person's latest ratings you're allowed to see. */
export function UserRatingsSection({
  userId,
  name,
  isSelf = false,
}: {
  userId: string;
  name: string;
  isSelf?: boolean;
}) {
  const router = useRouter();
  const ratings = useUserRatings(userId);
  return (
    <View testID="user-ratings" className="gap-3">
      <Heading size="sm">Ratings</Heading>
      <RatingsPreview
        testID="user-ratings"
        query={ratings}
        limit={USER_RATINGS_PREVIEW}
        showAuthor={false}
        emptyText={isSelf ? "You haven't rated anything yet." : `${name} hasn't shared any ratings with you yet.`}
        seeAllLabel={isSelf ? 'See all your ratings' : `See all of ${name}'s ratings`}
        onSeeAll={() => router.push(`/user/${userId}/ratings`)}
      />
    </View>
  );
}
