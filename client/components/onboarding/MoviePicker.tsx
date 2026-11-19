import { useMemo, useState, type Dispatch, type SetStateAction } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { flattenPages, SEARCH_MAX_LENGTH, useMovieSearch, usePopularMovies } from '../../api/movies';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { useTheme } from '../../theme/useTheme';
import type { MovieSummaryDTO } from '../../types/api';
import { Body, Card, ErrorState, Label, SearchBar } from '../ui';

const SEARCH_DEBOUNCE_MS = 300;

/** Swaps `list[i]` with its neighbour `i + delta` (no-op at the ends). */
export function moveItem<T>(list: T[], i: number, delta: -1 | 1): T[] {
  const j = i + delta;
  if (j < 0 || j >= list.length) return list;
  const next = [...list];
  [next[i], next[j]] = [next[j] as T, next[i] as T];
  return next;
}

/** `list` with `movie` toggled: removed when present, appended when there's room, else unchanged. */
export function togglePick(list: MovieSummaryDTO[], movie: MovieSummaryDTO, max: number): MovieSummaryDTO[] {
  if (list.some((m) => m.id === movie.id)) return list.filter((m) => m.id !== movie.id);
  return list.length >= max ? list : [...list, movie];
}

const yearTitle = (movie: MovieSummaryDTO) => (movie.releaseYear ? `${movie.title} (${movie.releaseYear})` : movie.title);

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
      className={`h-9 w-9 items-center justify-center rounded-full bg-raised active:opacity-70 ${disabled ? 'opacity-40' : ''}`}
    >
      <Ionicons name={icon} size={18} color={colors.text} />
    </Pressable>
  );
}

type PosterProps = {
  movie: MovieSummaryDTO;
  label: string;
  picked: boolean;
  /** Why it can't be picked here (it's in the other list); shown on the poster. */
  blockedReason?: string;
  onPress: () => void;
};

function PickPoster({ movie, label, picked, blockedReason, onPress }: PosterProps) {
  const { colors } = useTheme();
  const blocked = !!blockedReason;
  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityLabel={blocked ? `${label}, ${blockedReason.toLowerCase()}` : label}
      accessibilityState={{ checked: picked, disabled: blocked }}
      disabled={blocked}
      onPress={onPress}
      className="w-[30%] gap-1 active:opacity-80"
    >
      <View
        className={`aspect-[2/3] w-full overflow-hidden rounded-xl border-2 bg-raised ${picked ? 'border-accent' : 'border-transparent'} ${blocked ? 'opacity-40' : ''}`}
      >
        <View className="absolute inset-0 items-center justify-center p-1">
          <Text className="text-center font-display text-xs text-muted" numberOfLines={3}>
            {movie.title}
          </Text>
        </View>
        {movie.posterUrl ? (
          <Image source={{ uri: movie.posterUrl }} style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }} contentFit="cover" />
        ) : null}
        {picked ? (
          <View className="absolute right-1.5 top-1.5 h-6 w-6 items-center justify-center rounded-full bg-accent">
            <Ionicons name="checkmark" size={16} color={colors.onAccent} />
          </View>
        ) : null}
      </View>
      <Text className="font-body-medium text-xs text-text" numberOfLines={1}>
        {movie.title}
      </Text>
      {blocked ? (
        <Text className="font-body text-xs text-muted" numberOfLines={1}>
          {blockedReason}
        </Text>
      ) : null}
    </Pressable>
  );
}

type Props = {
  picks: MovieSummaryDTO[];
  setPicks: Dispatch<SetStateAction<MovieSummaryDTO[]>>;
  max: number;
  /** The list's name for screen readers, e.g. "favourites" ("Remove Heat from favourites"). */
  listName: string;
  /** Prefix for test ids: `${id}s-count`, `${id}-1`, `${id}s-results`, `${id}s-loading`. */
  testIDPrefix: string;
  searchLabel: string;
  /** Shown when there's no query and nothing popular to show. */
  emptyPrompt: string;
  /** The grid poster's accessibility label (defaults to "Title (Year)"). */
  pickLabel?: (movie: MovieSummaryDTO) => string;
  /** Movies that can't be picked into this list, with the reason shown on them. */
  blocked?: Map<number, string>;
  /** Called on every edit (to clear a stale save error). */
  onEdit?: () => void;
};

/**
 * A ranked pick list (reorder with up / down, remove) above a search bar and a poster grid (popular
 * when the search is empty). Shared by onboarding's favourites and least favourites.
 */
export function MoviePicker({
  picks,
  setPicks,
  max,
  listName,
  testIDPrefix,
  searchLabel,
  emptyPrompt,
  pickLabel = yearTitle,
  blocked,
  onEdit,
}: Props) {
  const { colors } = useTheme();
  const [input, setInput] = useState('');
  const query = useDebouncedValue(input.trim(), SEARCH_DEBOUNCE_MS);
  const search = useMovieSearch(query);
  const popular = usePopularMovies(null);
  const source = query ? search : popular;
  const results = useMemo(() => flattenPages(source.data), [source.data]);
  const pickedIds = new Set(picks.map((m) => m.id));

  const edit = (change: (list: MovieSummaryDTO[]) => MovieSummaryDTO[]) => {
    onEdit?.();
    setPicks(change);
  };
  const toggle = (movie: MovieSummaryDTO) => edit((list) => togglePick(list, movie, max));

  let grid;
  if (source.isPending && source.fetchStatus !== 'idle') {
    grid = <ActivityIndicator testID={`${testIDPrefix}s-loading`} color={colors.accent} className="py-6" />;
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
        {query ? `Nothing found for “${query}”.` : emptyPrompt}
      </Body>
    );
  } else {
    grid = (
      <View testID={`${testIDPrefix}s-results`} className="flex-row flex-wrap gap-3">
        {results.map((m) => (
          <PickPoster
            key={m.id}
            movie={m}
            label={pickLabel(m)}
            picked={pickedIds.has(m.id)}
            blockedReason={pickedIds.has(m.id) ? undefined : blocked?.get(m.id)}
            onPress={() => toggle(m)}
          />
        ))}
      </View>
    );
  }

  return (
    <>
      <Card className="gap-2">
        <Label testID={`${testIDPrefix}s-count`}>
          {picks.length} of {max} picked
        </Label>
        {picks.map((m, i) => (
          <View key={m.id} testID={`${testIDPrefix}-${i + 1}`} className="flex-row items-center gap-2">
            <Text className="w-6 font-body-semibold text-base text-accent-ink">{i + 1}</Text>
            <Text className="flex-1 font-body-medium text-base text-text" numberOfLines={1}>
              {m.title}
            </Text>
            <IconButton
              icon="chevron-up"
              label={`Move ${m.title} up in ${listName}`}
              disabled={i === 0}
              onPress={() => edit((l) => moveItem(l, i, -1))}
            />
            <IconButton
              icon="chevron-down"
              label={`Move ${m.title} down in ${listName}`}
              disabled={i === picks.length - 1}
              onPress={() => edit((l) => moveItem(l, i, 1))}
            />
            <IconButton icon="close" label={`Remove ${m.title} from ${listName}`} onPress={() => toggle(m)} />
          </View>
        ))}
      </Card>
      <SearchBar label={searchLabel} placeholder="Search movies" value={input} onChangeText={setInput} maxLength={SEARCH_MAX_LENGTH} />
      {!query ? <Label>Popular right now</Label> : null}
      {grid}
    </>
  );
}
