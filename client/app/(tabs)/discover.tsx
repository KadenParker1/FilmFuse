import { useMemo, useState } from 'react';
import { ScrollView, View } from 'react-native';
import { useRouter } from 'expo-router';
import { flattenPages, SEARCH_MAX_LENGTH, useGenres, useMovieSearch, usePopularMovies } from '../../api/movies';
import {
  Chip,
  EmptyState,
  ErrorState,
  Heading,
  Label,
  PosterGrid,
  Screen,
  SearchBar,
  Skeleton,
  type PosterItem,
} from '../../components/ui';
import { TmdbAttribution } from '../../components/TmdbAttribution';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import type { MovieSummaryDTO } from '../../types/api';

const SEARCH_DEBOUNCE_MS = 300;

const toPosterItem = (m: MovieSummaryDTO): PosterItem => ({
  id: m.id,
  title: m.title,
  posterUrl: m.posterUrl,
  subtitle: m.releaseYear != null ? String(m.releaseYear) : undefined,
});

function GenreChips({ selected, onSelect }: { selected: number | null; onSelect: (id: number | null) => void }) {
  const genres = useGenres();
  if (genres.isError) return null; // Popular still works without the filter row.
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      contentContainerClassName="gap-2 pr-4"
      accessibilityLabel="Filter by genre"
    >
      {genres.isPending ? (
        Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-9 w-24 rounded-full" />)
      ) : (
        <>
          <Chip label="All" selected={selected === null} onPress={() => onSelect(null)} />
          {genres.data.map((g) => (
            <Chip
              key={g.id}
              label={g.name}
              selected={selected === g.id}
              onPress={() => onSelect(selected === g.id ? null : g.id)}
            />
          ))}
        </>
      )}
    </ScrollView>
  );
}

export default function DiscoverScreen() {
  const router = useRouter();
  const [input, setInput] = useState('');
  const [genreId, setGenreId] = useState<number | null>(null);
  const query = useDebouncedValue(input.trim(), SEARCH_DEBOUNCE_MS);
  const searching = query.length > 0;

  const search = useMovieSearch(query);
  const popular = usePopularMovies(genreId);
  const active = searching ? search : popular;

  const items = useMemo(() => flattenPages(active.data).map(toPosterItem), [active.data]);
  const openMovie = (item: PosterItem) => router.push(`/movie/${item.id}`);
  const loadMore = () => {
    if (active.hasNextPage && !active.isFetchingNextPage && !active.isError) void active.fetchNextPage();
  };

  const header = searching ? (
    <Label className="pb-1">Results for “{query}”</Label>
  ) : (
    <View className="gap-4 pb-1">
      <GenreChips selected={genreId} onSelect={setGenreId} />
      <Heading size="sm">Popular</Heading>
    </View>
  );

  // A failed first page replaces the grid; a failed later page shows a retry in the footer.
  const firstPageFailed = active.isError && items.length === 0;
  const footer = (
    <>
      {active.isError && items.length > 0 ? (
        <ErrorState
          compact
          title="Couldn't load more"
          error={active.error}
          onRetry={() => void active.fetchNextPage()}
          retrying={active.isFetchingNextPage}
        />
      ) : null}
      <TmdbAttribution />
    </>
  );

  return (
    <Screen edges={['top']} wide className="gap-4 pb-0">
      <Heading>Discover</Heading>
      <SearchBar
        label="Search movies"
        placeholder="Search movies"
        value={input}
        onChangeText={setInput}
        maxLength={SEARCH_MAX_LENGTH}
      />
      {firstPageFailed ? (
        <View className="flex-1">
          {header}
          <ErrorState
            title={searching ? 'Search failed' : "Couldn't load movies"}
            error={active.error}
            onRetry={() => void active.refetch()}
            retrying={active.isFetching}
          />
          <TmdbAttribution />
        </View>
      ) : (
        <PosterGrid
          items={items}
          loading={active.isPending && active.fetchStatus !== 'idle'}
          loadingMore={active.isFetchingNextPage}
          onEndReached={loadMore}
          onPressItem={openMovie}
          header={header}
          footer={footer}
          empty={
            searching ? (
              <EmptyState icon="search-outline" title="No matches" message={`Nothing found for “${query}”. Try another title.`} />
            ) : (
              <EmptyState icon="film-outline" title="Nothing showing" message="No popular movies in this genre right now." />
            )
          }
        />
      )}
    </Screen>
  );
}
