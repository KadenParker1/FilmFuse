import { useMemo } from 'react';
import { ActivityIndicator, FlatList, View } from 'react-native';
import { useRouter } from 'expo-router';
import { flattenPages } from '../../api/movies';
import { isSearchableUserQuery, USER_SEARCH_MIN_LENGTH, useUserSearch } from '../../api/users';
import { useTheme } from '../../theme/useTheme';
import { Body, EmptyState, ErrorState, Label } from '../ui';
import { FriendshipActionButton } from './FriendshipActionButton';
import { UserRow, UserRowSkeletons } from './UserRow';

/** People-search results for an already-debounced, trimmed query. */
export function UserSearchResults({ query }: { query: string }) {
  const router = useRouter();
  const { colors } = useTheme();
  const search = useUserSearch(query);
  const items = useMemo(() => flattenPages(search.data), [search.data]);

  if (!isSearchableUserQuery(query)) {
    return (
      <Body testID="search-hint" muted className="py-4 text-center">
        Type at least {USER_SEARCH_MIN_LENGTH} characters to search for people.
      </Body>
    );
  }
  if (search.isPending) return <UserRowSkeletons count={5} testID="search-loading" />;
  if (search.isError && items.length === 0) {
    return (
      <ErrorState
        title="Search failed"
        error={search.error}
        onRetry={() => void search.refetch()}
        retrying={search.isFetching}
      />
    );
  }

  const loadMore = () => {
    if (search.hasNextPage && !search.isFetchingNextPage && !search.isError) void search.fetchNextPage();
  };

  return (
    <FlatList
      testID="user-search-results"
      data={items}
      keyExtractor={(u) => u.id}
      keyboardShouldPersistTaps="handled"
      keyboardDismissMode="on-drag"
      contentContainerStyle={{ paddingBottom: 120 }}
      onEndReachedThreshold={0.5}
      onEndReached={loadMore}
      ListHeaderComponent={<Label className="pb-1">People matching “{query}”</Label>}
      ListEmptyComponent={
        <EmptyState
          icon="person-outline"
          title="No one found"
          message={`No one matches “${query}”. Check the spelling, or send them an invite link.`}
        />
      }
      ListFooterComponent={
        <View>
          {search.isFetchingNextPage ? (
            <ActivityIndicator testID="search-loading-more" color={colors.gold} className="py-4" />
          ) : null}
          {search.isError && items.length > 0 ? (
            <ErrorState
              compact
              title="Couldn't load more"
              error={search.error}
              onRetry={() => void search.fetchNextPage()}
              retrying={search.isFetchingNextPage}
            />
          ) : null}
        </View>
      }
      renderItem={({ item }) => (
        <UserRow user={item} onPress={() => router.push(`/user/${item.id}`)}>
          <FriendshipActionButton user={item} state={item.friendship} />
        </UserRow>
      )}
    />
  );
}
