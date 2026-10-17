import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { flattenPages, SEARCH_MAX_LENGTH, useMovieSearch } from '../../api/movies';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { useTheme } from '../../theme/useTheme';
import type { MovieSummaryDTO } from '../../types/api';
import { Body, ErrorState, SearchBar } from '../ui';

const SEARCH_DEBOUNCE_MS = 300;

/** "What did you watch?": movie search whose results are tapped to pick one. */
export function MoviePicker({ onPick }: { onPick: (movie: MovieSummaryDTO) => void }) {
  const { colors } = useTheme();
  const [input, setInput] = useState('');
  const query = useDebouncedValue(input.trim(), SEARCH_DEBOUNCE_MS);
  const search = useMovieSearch(query);
  const items = useMemo(() => flattenPages(search.data), [search.data]);

  let results;
  if (!query) {
    results = (
      <Body muted testID="movie-picker-hint" className="py-4 text-center">
        Search for the movie you watched.
      </Body>
    );
  } else if (search.isPending) {
    results = <ActivityIndicator testID="movie-picker-loading" color={colors.accent} className="py-6" />;
  } else if (search.isError) {
    results = (
      <ErrorState compact title="Search failed" error={search.error} onRetry={() => void search.refetch()} />
    );
  } else if (items.length === 0) {
    results = (
      <Body muted className="py-4 text-center">
        Nothing found for “{query}”.
      </Body>
    );
  } else {
    results = items.map((m) => (
      <Pressable
        key={m.id}
        accessibilityRole="button"
        accessibilityLabel={`Rate ${m.title}${m.releaseYear ? ` (${m.releaseYear})` : ''}`}
        onPress={() => onPick(m)}
        className="flex-row items-center gap-3 rounded-xl py-2 active:opacity-70"
      >
        <View className="h-16 w-11 overflow-hidden rounded-lg bg-raised">
          {m.posterUrl ? (
            <Image source={{ uri: m.posterUrl }} style={{ width: '100%', height: '100%' }} contentFit="cover" />
          ) : null}
        </View>
        <View className="flex-1">
          <Text className="font-body-semibold text-base text-text" numberOfLines={1}>
            {m.title}
          </Text>
          {m.releaseYear ? <Text className="font-body text-sm text-muted">{m.releaseYear}</Text> : null}
        </View>
      </Pressable>
    ));
  }

  return (
    <View className="gap-3">
      <SearchBar
        label="Search movies to rate"
        placeholder="What did you watch?"
        value={input}
        onChangeText={setInput}
        maxLength={SEARCH_MAX_LENGTH}
        autoFocus
      />
      <ScrollView style={{ maxHeight: 360 }} keyboardShouldPersistTaps="handled">
        {results}
      </ScrollView>
    </View>
  );
}
