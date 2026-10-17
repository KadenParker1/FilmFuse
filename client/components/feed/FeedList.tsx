import { useMemo, type ReactElement } from 'react';
import { ActivityIndicator, FlatList, View } from 'react-native';
import type { InfiniteData, UseInfiniteQueryResult } from '@tanstack/react-query';
import { flattenPages } from '../../api/movies';
import { useTheme } from '../../theme/useTheme';
import type { FeedItemDTO, PageDTO } from '../../types/api';
import { ErrorState } from '../ui';
import { FeedCard, FeedCardSkeletons } from './FeedCard';

type FeedQuery = Pick<
  UseInfiniteQueryResult<InfiniteData<PageDTO<FeedItemDTO>>>,
  | 'data'
  | 'isPending'
  | 'isError'
  | 'error'
  | 'isFetching'
  | 'isRefetching'
  | 'hasNextPage'
  | 'isFetchingNextPage'
  | 'fetchNextPage'
  | 'refetch'
>;

type Props = {
  query: FeedQuery;
  /** Shown when the first page comes back empty. */
  empty: ReactElement;
  /** Above the cards (scrolls with them). */
  header?: ReactElement;
  errorTitle?: string;
  showAuthor?: boolean;
  showMovie?: boolean;
  testID?: string;
};

/** A virtualised, infinite list of feed cards with pull-to-refresh, skeletons, empty and error states. */
export function FeedList({
  query,
  empty,
  header,
  errorTitle = "Couldn't load ratings",
  showAuthor,
  showMovie,
  testID = 'feed-list',
}: Props) {
  const { colors } = useTheme();
  const items = useMemo(() => flattenPages(query.data), [query.data]);

  let body: ReactElement | null = null;
  if (query.isPending) body = <FeedCardSkeletons testID={`${testID}-loading`} />;
  else if (query.isError && items.length === 0)
    body = (
      <ErrorState
        title={errorTitle}
        error={query.error}
        onRetry={() => void query.refetch()}
        retrying={query.isFetching}
      />
    );

  const loadMore = () => {
    if (query.hasNextPage && !query.isFetchingNextPage && !query.isError) void query.fetchNextPage();
  };
  // Only a user-started refresh shows the spinner, not a background refetch or a next page.
  const refreshing = !!query.isRefetching && !query.isFetchingNextPage;

  return (
    <FlatList
      testID={testID}
      data={body ? [] : items}
      keyExtractor={(item) => item.id}
      contentContainerStyle={{ paddingBottom: 120, gap: 12, flexGrow: 1 }}
      showsVerticalScrollIndicator={false}
      onEndReachedThreshold={0.5}
      onEndReached={loadMore}
      refreshing={refreshing}
      onRefresh={() => void query.refetch()}
      ListHeaderComponent={header}
      ListEmptyComponent={body ?? empty}
      ListFooterComponent={
        <View>
          {query.isFetchingNextPage ? (
            <ActivityIndicator testID={`${testID}-loading-more`} color={colors.accent} className="py-4" />
          ) : null}
          {query.isError && items.length > 0 ? (
            <ErrorState
              compact
              title="Couldn't load more"
              error={query.error}
              onRetry={() => void query.fetchNextPage()}
              retrying={query.isFetchingNextPage}
            />
          ) : null}
        </View>
      }
      renderItem={({ item }) => <FeedCard item={item} showAuthor={showAuthor} showMovie={showMovie} />}
    />
  );
}
