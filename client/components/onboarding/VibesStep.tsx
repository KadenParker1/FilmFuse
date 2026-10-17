import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { useMoods } from '../../api/moods';
import { useSaveMoodPrefs } from '../../api/onboarding';
import type { OnboardingStateDTO } from '../../types/api';
import { PrefSliderRow } from '../taste/PrefSliderRow';
import { Button, ErrorState, Skeleton } from '../ui';
import { OnboardingFrame } from './OnboardingFrame';
import { describeError, NEUTRAL_SCORE, prefsToSend } from './steps';
import { useAdvance } from './useAdvance';

/** How many vibes show before "More vibes". */
export const VIBES_SHOWN = 4;

/** Step 5: 1–10 sliders for moods. Optional: untouched vibes aren't saved. */
export function VibesStep({ state }: { state: OnboardingStateDTO }) {
  const moods = useMoods();
  const save = useSaveMoodPrefs();
  const nav = useAdvance(5);
  const saved = useMemo(() => new Set(state.moodPrefs.map((p) => p.moodTag)), [state.moodPrefs]);
  const [values, setValues] = useState(() => new Map(state.moodPrefs.map((p) => [p.moodTag, p.score])));
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const [expanded, setExpanded] = useState(false);

  const set = (tag: string, score: number) => {
    save.reset();
    setValues((v) => new Map(v).set(tag, score));
    setTouched((t) => new Set(t).add(tag));
  };

  async function next() {
    nav.reset();
    const tags = moods.data?.map((m) => m.tag) ?? [];
    const prefs = prefsToSend(tags, values, saved, touched).map(([moodTag, score]) => ({ moodTag, score }));
    if (touched.size > 0) {
      try {
        await save.mutateAsync(prefs);
      } catch {
        return;
      }
    }
    await nav.advance();
  }

  let content;
  if (moods.isPending) {
    content = (
      <View testID="vibes-loading" className="gap-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-28 w-full" />
        ))}
      </View>
    );
  } else if (moods.isError) {
    content = (
      <ErrorState
        title="Couldn't load vibes"
        error={moods.error}
        onRetry={() => void moods.refetch()}
        retrying={moods.isFetching}
      />
    );
  } else {
    const shown = expanded ? moods.data : moods.data.slice(0, VIBES_SHOWN);
    content = (
      <>
        {shown.map((m) => (
          <PrefSliderRow
            key={m.tag}
            testID={`mood-row-${m.tag}`}
            label={m.label}
            value={values.get(m.tag) ?? NEUTRAL_SCORE}
            onChange={(s) => set(m.tag, s)}
          />
        ))}
        {!expanded && moods.data.length > VIBES_SHOWN ? (
          <Button title="More vibes" variant="ghost" onPress={() => setExpanded(true)} />
        ) : null}
      </>
    );
  }

  return (
    <OnboardingFrame
      step={5}
      title="Pick your vibes"
      subtitle="How much do you go for each kind of mood? Skip any you’re unsure about."
      onBack={nav.back}
      onContinue={() => void next()}
      loading={save.isPending || nav.isPending}
      error={describeError(save.error ?? nav.error)}
    >
      {content}
    </OnboardingFrame>
  );
}
