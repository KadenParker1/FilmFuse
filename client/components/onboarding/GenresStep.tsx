import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { useGenres } from '../../api/movies';
import { useSaveGenrePrefs } from '../../api/onboarding';
import type { OnboardingStateDTO } from '../../types/api';
import { PrefSliderRow } from '../taste/PrefSliderRow';
import { ErrorState, Skeleton } from '../ui';
import { OnboardingFrame } from './OnboardingFrame';
import { describeError, genreGate, NEUTRAL_SCORE, prefsToSend } from './steps';
import { useAdvance } from './useAdvance';

/** Step 2: a 1–10 slider per genre, starting at 5. Needs ≥5 moved off 5 and ≥3 at 8+. */
export function GenresStep({ state }: { state: OnboardingStateDTO }) {
  const genres = useGenres();
  const save = useSaveGenrePrefs();
  const nav = useAdvance(2);
  const saved = useMemo(() => new Set(state.genrePrefs.map((p) => p.genreId)), [state.genrePrefs]);
  const [values, setValues] = useState(() => new Map(state.genrePrefs.map((p) => [p.genreId, p.score])));
  const [touched, setTouched] = useState<Set<number>>(() => new Set());

  const ids = useMemo(() => genres.data?.map((g) => g.id) ?? [], [genres.data]);
  const gate = genreGate(ids.map((id) => values.get(id) ?? NEUTRAL_SCORE));

  const set = (id: number, score: number) => {
    save.reset();
    setValues((v) => new Map(v).set(id, score));
    setTouched((t) => new Set(t).add(id));
  };

  async function next() {
    nav.reset();
    const prefs = prefsToSend(ids, values, saved, touched).map(([genreId, score]) => ({ genreId, score }));
    try {
      await save.mutateAsync(prefs);
    } catch {
      return;
    }
    await nav.advance();
  }

  let content;
  if (genres.isPending) {
    content = (
      <View testID="genres-loading" className="gap-3">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-28 w-full" />
        ))}
      </View>
    );
  } else if (genres.isError) {
    content = (
      <ErrorState
        title="Couldn't load genres"
        error={genres.error}
        onRetry={() => void genres.refetch()}
        retrying={genres.isFetching}
      />
    );
  } else {
    content = genres.data.map((g) => (
      <PrefSliderRow
        key={g.id}
        testID={`genre-row-${g.id}`}
        label={g.name}
        value={values.get(g.id) ?? NEUTRAL_SCORE}
        onChange={(s) => set(g.id, s)}
      />
    ));
  }

  return (
    <OnboardingFrame
      step={2}
      title="What do you like to watch?"
      subtitle="Slide each genre from 1 (can’t stand) to 10 (all-time). Leave the ones you don’t care about at 5."
      onBack={nav.back}
      onContinue={() => void next()}
      continueDisabled={!genres.isSuccess || !gate.ok}
      loading={save.isPending || nav.isPending}
      hint={genres.isSuccess ? gate.hint : null}
      error={describeError(save.error ?? nav.error)}
    >
      {content}
    </OnboardingFrame>
  );
}
