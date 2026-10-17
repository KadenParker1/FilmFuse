import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { flattenPages, SEARCH_MAX_LENGTH, useMovieSearch, usePopularMovies } from '../../api/movies';
import { useSaveFavorites } from '../../api/onboarding';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { useTheme } from '../../theme/useTheme';
import type { MovieSummaryDTO, OnboardingStateDTO } from '../../types/api';
import { Body, Card, ErrorState, Label, SearchBar } from '../ui';
import { OnboardingFrame } from './OnboardingFrame';
import { describeError, FAVORITES_MAX, FAVORITES_MIN } from './steps';
import { useAdvance } from './useAdvance';

const SEARCH_DEBOUNCE_MS = 300;

/** Swaps `list[i]` with its neighbour `i + delta` (no-op at the ends). */
export function moveItem<T>(list: T[], i: number, delta: -1 | 1): T[] {
  const j = i + delta;
  if (j < 0 || j >= list.length) return list;
  const next = [...list];
  [next[i], next[j]] = [next[j] as T, next[i] as T];
  return next;
}

function IconButton({ icon, label, onPress, disabled }: { icon: 'chevron-up' | 'chevron-down' | 'close'; label: string; onPress: () => void; disabled?: boolean }) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: !!disabled }}
      disabled={disabled}
      hitSlop={6}
      onPress={onPress}
      className={`h-9 w-9 items-center justify-center rounded-full border border-border bg-surface-raised active:opacity-70 ${disabled ? 'opacity-40' : ''}`}
    >
      <Ionicons name={icon} size={18} color={colors.text} />
    </Pressable>
  );
}

function PickPoster({ movie, picked, onPress }: { movie: MovieSummaryDTO; picked: boolean; onPress: () => void }) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityLabel={movie.releaseYear ? `${movie.title} (${movie.releaseYear})` : movie.title}
      accessibilityState={{ checked: picked }}
      onPress={onPress}
      className="w-[30%] gap-1 active:opacity-80"
    >
      <View
        className={`aspect-[2/3] w-full overflow-hidden rounded-xl border-2 bg-surface-raised ${picked ? 'border-gold' : 'border-transparent'}`}
      >
        <View className="absolute inset-0 items-center justify-center p-1">
          <Text className="text-center font-display text-xs text-text-muted" numberOfLines={3}>
            {movie.title}
          </Text>
        </View>
        {movie.posterUrl ? (
          <Image source={{ uri: movie.posterUrl }} style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }} contentFit="cover" />
        ) : null}
        {picked ? (
          <View className="absolute right-1.5 top-1.5 h-6 w-6 items-center justify-center rounded-full bg-gold">
            <Ionicons name="checkmark" size={16} color={colors.onGold} />
          </View>
        ) : null}
      </View>
      <Text className="font-body-medium text-xs text-text" numberOfLines={1}>
        {movie.title}
      </Text>
    </Pressable>
  );
}

/** Step 3: search (or browse popular) and pick 3–10 all-time favourites, ranked with up / down. */
export function FavoritesStep({ state }: { state: OnboardingStateDTO }) {
  const { colors } = useTheme();
  const save = useSaveFavorites();
  const nav = useAdvance(3);
  const [picks, setPicks] = useState<MovieSummaryDTO[]>(() =>
    [...state.favorites].sort((a, b) => a.rank - b.rank).map((f) => f.movie),
  );
  const [input, setInput] = useState('');
  const query = useDebouncedValue(input.trim(), SEARCH_DEBOUNCE_MS);
  const search = useMovieSearch(query);
  const popular = usePopularMovies(null);
  const source = query ? search : popular;
  const results = useMemo(() => flattenPages(source.data), [source.data]);
  const pickedIds = new Set(picks.map((m) => m.id));
  const full = picks.length >= FAVORITES_MAX;

  const toggle = (movie: MovieSummaryDTO) => {
    save.reset();
    setPicks((list) =>
      list.some((m) => m.id === movie.id)
        ? list.filter((m) => m.id !== movie.id)
        : list.length >= FAVORITES_MAX
          ? list
          : [...list, movie],
    );
  };

  async function next() {
    nav.reset();
    try {
      await save.mutateAsync(picks.map((m, i) => ({ movieId: m.id, rank: i + 1 })));
    } catch {
      return;
    }
    await nav.advance();
  }

  const remaining = FAVORITES_MIN - picks.length;
  const hint =
    remaining > 0
      ? `Pick ${remaining} more to continue.`
      : full
        ? `That’s the maximum of ${FAVORITES_MAX}. Remove one to swap it.`
        : null;

  let grid;
  if (source.isPending && source.fetchStatus !== 'idle') {
    grid = <ActivityIndicator testID="favorites-loading" color={colors.gold} className="py-6" />;
  } else if (source.isError) {
    grid = (
      <ErrorState
        compact
        title={query ? 'Search failed' : "Couldn't load movies"}
        error={source.error}
        onRetry={() => void source.refetch()}
        retrying={source.isFetching}
      />
    );
  } else if (results.length === 0) {
    grid = (
      <Body muted className="py-4 text-center">
        {query ? `Nothing found for “${query}”.` : 'Search for a movie you love.'}
      </Body>
    );
  } else {
    grid = (
      <View testID="favorites-results" className="flex-row flex-wrap gap-3">
        {results.map((m) => (
          <PickPoster key={m.id} movie={m} picked={pickedIds.has(m.id)} onPress={() => toggle(m)} />
        ))}
      </View>
    );
  }

  return (
    <OnboardingFrame
      step={3}
      title="All-time favourites"
      subtitle={`Pick ${FAVORITES_MIN} to ${FAVORITES_MAX} movies you’d happily watch again. Put your very favourite first.`}
      onBack={nav.back}
      onContinue={() => void next()}
      continueDisabled={picks.length < FAVORITES_MIN}
      loading={save.isPending || nav.isPending}
      hint={hint}
      error={describeError(save.error ?? nav.error)}
    >
      <Card className="gap-2">
        <Label testID="favorites-count">
          {picks.length} of {FAVORITES_MAX} picked
        </Label>
        {picks.map((m, i) => (
          <View key={m.id} testID={`favorite-${i + 1}`} className="flex-row items-center gap-2">
            <Text className="w-6 font-body-semibold text-base text-gold">{i + 1}</Text>
            <Text className="flex-1 font-body-medium text-base text-text" numberOfLines={1}>
              {m.title}
            </Text>
            <IconButton icon="chevron-up" label={`Move ${m.title} up`} disabled={i === 0} onPress={() => setPicks((l) => moveItem(l, i, -1))} />
            <IconButton
              icon="chevron-down"
              label={`Move ${m.title} down`}
              disabled={i === picks.length - 1}
              onPress={() => setPicks((l) => moveItem(l, i, 1))}
            />
            <IconButton icon="close" label={`Remove ${m.title}`} onPress={() => toggle(m)} />
          </View>
        ))}
      </Card>
      <SearchBar
        label="Search favourite movies"
        placeholder="Search movies"
        value={input}
        onChangeText={setInput}
        maxLength={SEARCH_MAX_LENGTH}
      />
      {!query ? <Label>Popular right now</Label> : null}
      {grid}
    </OnboardingFrame>
  );
}
