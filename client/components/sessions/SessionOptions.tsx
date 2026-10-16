import { Pressable, ScrollView, Text, View } from 'react-native';
import { useGenres } from '../../api/movies';
import type { SessionFiltersDTO, Strategy } from '../../types/api';
import { Chip, ChoiceGroup, Label, Skeleton, TextField } from '../ui';
import { RUNTIME_CHOICES, STRATEGIES } from './sessionCopy';

export type SessionFilterState = {
  maxRuntime: number | null;
  /** Raw text of the "from year" field; validated on submit. */
  fromYear: string;
  genreIds: number[];
};

export const EMPTY_FILTERS: SessionFilterState = { maxRuntime: null, fromYear: '', genreIds: [] };

/** The filters payload, or null when nothing is set. */
export function toFiltersDTO(state: SessionFilterState, minYear: number | null): SessionFiltersDTO | null {
  const dto: SessionFiltersDTO = {
    maxRuntime: state.maxRuntime,
    minYear,
    genreIds: state.genreIds.length ? state.genreIds : null,
  };
  return dto.maxRuntime == null && dto.minYear == null && dto.genreIds == null ? null : dto;
}

type FiltersProps = {
  value: SessionFilterState;
  onChange: (value: SessionFilterState) => void;
  yearError?: string | null;
};

/** Optional session filters: max runtime chips, a from-year field and genre chips. */
export function SessionFiltersForm({ value, onChange, yearError }: FiltersProps) {
  const genres = useGenres();
  const toggleGenre = (id: number) =>
    onChange({
      ...value,
      genreIds: value.genreIds.includes(id) ? value.genreIds.filter((g) => g !== id) : [...value.genreIds, id],
    });

  return (
    <View className="gap-4">
      <View className="gap-2">
        <Label accessibilityRole="header">Length</Label>
        <ChoiceGroup
          label="Maximum runtime"
          choices={RUNTIME_CHOICES}
          value={value.maxRuntime}
          onChange={(maxRuntime) => onChange({ ...value, maxRuntime })}
        />
      </View>
      <TextField
        label="Released from (year)"
        placeholder="Any year"
        keyboardType="number-pad"
        maxLength={4}
        value={value.fromYear}
        onChangeText={(fromYear) => onChange({ ...value, fromYear })}
        error={yearError}
      />
      <View className="gap-2">
        <Label accessibilityRole="header">Genres</Label>
        {genres.isPending ? (
          <View testID="genres-loading" accessibilityLabel="Loading genres">
            <Skeleton className="h-9 w-full rounded-full" />
          </View>
        ) : genres.isError ? (
          <Text className="font-body text-sm text-text-muted">Genres unavailable right now.</Text>
        ) : (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerClassName="gap-2">
            {genres.data.map((g) => (
              <Chip key={g.id} label={g.name} selected={value.genreIds.includes(g.id)} onPress={() => toggleGenre(g.id)} />
            ))}
          </ScrollView>
        )}
      </View>
    </View>
  );
}

/** How the group's tastes are fused, each with a one-line explanation. */
export function StrategyPicker({ value, onChange }: { value: Strategy; onChange: (s: Strategy) => void }) {
  return (
    <View className="gap-2">
      <Label accessibilityRole="header">How to choose</Label>
      <View accessibilityRole="radiogroup" accessibilityLabel="How to choose" className="gap-2">
        {STRATEGIES.map((s) => {
          const selected = s.value === value;
          return (
            <Pressable
              key={s.value}
              accessibilityRole="radio"
              accessibilityLabel={`${s.label}: ${s.description}`}
              accessibilityState={{ checked: selected }}
              onPress={() => onChange(s.value)}
              className={`rounded-xl border px-4 py-3 active:opacity-80 ${
                selected ? 'border-gold bg-surface-raised' : 'border-border bg-surface'
              }`}
            >
              <Text className={`font-body-semibold text-base ${selected ? 'text-gold' : 'text-text'}`}>{s.label}</Text>
              <Text className="font-body text-sm text-text-muted">{s.description}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}
