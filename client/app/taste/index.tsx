import { useState } from 'react';
import { Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSaveGenrePrefs, useSaveMoodPrefs } from '../../api/onboarding';
import { useTasteProfile } from '../../api/taste';
import { BackButton } from '../../components/BackButton';
import { describeError, NEUTRAL_SCORE } from '../../components/onboarding/steps';
import { useQuickRate } from '../../components/rating/QuickRateProvider';
import { LearnedMarker } from '../../components/taste/LearnedMarker';
import { PersonaCard } from '../../components/taste/PersonaCard';
import { PrefSliderRow } from '../../components/taste/PrefSliderRow';
import { parseDimensionKey, statedVsLearned } from '../../components/taste/tasteCopy';
import { Body, Button, ErrorState, Heading, Screen, Skeleton } from '../../components/ui';
import { useTheme } from '../../theme/useTheme';
import type { TasteDimensionDTO, TasteProfileDTO } from '../../types/api';

type Dim = TasteDimensionDTO & { kind: 'genre' | 'mood'; id: string };

function withKinds(dimensions: TasteDimensionDTO[]): Dim[] {
  return dimensions.flatMap((d) => {
    const parsed = parseDimensionKey(d.key);
    return parsed ? [{ ...d, ...parsed }] : [];
  });
}

function TasteEditor({ profile }: { profile: TasteProfileDTO }) {
  const router = useRouter();
  const { colors } = useTheme();
  const { showTasteUpdating } = useQuickRate();
  const saveGenres = useSaveGenrePrefs();
  const saveMoods = useSaveMoodPrefs();
  const [draft, setDraft] = useState<Map<string, number>>(() => new Map());
  const [saved, setSaved] = useState(false);
  const dims = withKinds(profile.dimensions);
  const busy = saveGenres.isPending || saveMoods.isPending;
  const dirty = draft.size > 0;

  const valueOf = (d: Dim) => draft.get(d.key) ?? d.stated;
  const set = (key: string, score: number) => {
    saveGenres.reset();
    saveMoods.reset();
    setSaved(false);
    setDraft((m) => new Map(m).set(key, score));
  };

  /** Replace-all PUTs: every dimension with a stated value plus every one moved here. */
  const toSend = (kind: 'genre' | 'mood') =>
    dims.filter((d) => d.kind === kind && valueOf(d) != null).map((d) => ({ id: d.id, score: valueOf(d) as number }));

  async function save() {
    const touchedKinds = new Set(dims.filter((d) => draft.has(d.key)).map((d) => d.kind));
    try {
      if (touchedKinds.has('genre')) {
        await saveGenres.mutateAsync(toSend('genre').map(({ id, score }) => ({ genreId: Number(id), score })));
      }
      if (touchedKinds.has('mood')) {
        await saveMoods.mutateAsync(toSend('mood').map(({ id, score }) => ({ moodTag: id, score })));
      }
    } catch {
      return;
    }
    setDraft(new Map());
    setSaved(true);
    showTasteUpdating();
  }

  const section = (kind: 'genre' | 'mood', title: string) => (
    <View className="gap-3">
      <Heading size="sm">{title}</Heading>
      {dims
        .filter((d) => d.kind === kind)
        .map((d) => {
          const stated = valueOf(d);
          return (
            <PrefSliderRow
              key={d.key}
              testID={`taste-row-${d.key}`}
              label={d.label}
              value={stated ?? NEUTRAL_SCORE}
              onChange={(s) => set(d.key, s)}
            >
              <LearnedMarker learned={d.learned} testID={`learned-${d.key}`} />
              <Text testID={`stated-vs-learned-${d.key}`} className="font-body text-sm text-muted">
                {statedVsLearned(stated, d.learned)}
              </Text>
            </PrefSliderRow>
          );
        })}
    </View>
  );

  const error = describeError(saveGenres.error ?? saveMoods.error);

  return (
    <View className="gap-6 pb-12">
      <PersonaCard profile={profile} />
      <Body muted>
        Sliders are what you tell us. The red dot is what your ratings say. Both shape your picks.
      </Body>
      <View className="flex-row gap-3">
        <Button
          title="Recalibrate"
          variant="secondary"
          className="flex-1"
          icon={<Ionicons name="albums-outline" size={18} color={colors.text} />}
          onPress={() => router.push('/taste/recalibrate')}
        />
        <Button
          title="How your taste changed"
          variant="secondary"
          className="flex-1"
          icon={<Ionicons name="time-outline" size={18} color={colors.text} />}
          onPress={() => router.push('/taste/history')}
        />
      </View>
      {section('genre', 'Genres')}
      {section('mood', 'Vibes')}
      {error ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {error}
        </Body>
      ) : saved ? (
        <Body accessibilityLiveRegion="polite" className="text-sm text-success">
          Saved. Your taste is updating.
        </Body>
      ) : null}
      <Button title="Save changes" loading={busy} disabled={!dirty} onPress={() => void save()} />
    </View>
  );
}

/** Profile → Edit taste: stated sliders next to learned values, recalibrate and history. */
export default function TasteEditorScreen() {
  const taste = useTasteProfile();

  let content;
  if (taste.isPending) {
    content = (
      <View testID="taste-editor-loading" className="gap-4">
        <Skeleton className="h-48 w-full" />
        <Skeleton className="h-28 w-full" />
      </View>
    );
  } else if (taste.isError) {
    content = (
      <ErrorState
        title="Couldn't load your taste"
        error={taste.error}
        onRetry={() => void taste.refetch()}
        retrying={taste.isFetching}
      />
    );
  } else {
    content = <TasteEditor profile={taste.data} />;
  }

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback="/profile" />
        <Heading size="md">Your taste</Heading>
      </View>
      {content}
    </Screen>
  );
}
