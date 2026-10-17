import { useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { useCalibrationBatch, useSubmitCalibration } from '../../api/onboarding';
import { useTheme } from '../../theme/useTheme';
import type { CalibrationRatingDTO } from '../../types/api';
import { Body, Button, Card, ErrorState, Slider1to10, errorMessage } from '../ui';

const START_SCORE = 5;

type Props = {
  /** Keeps this deck's batches apart from another deck's (onboarding vs recalibrate). */
  deckId: string;
  /** "Done" appears once this many are rated (counting `initialRatedCount`). 0 = always. */
  minRated: number;
  /** "Skip for now" appears after this many cards were answered in this sitting; null = never. */
  skipAfter: number | null;
  /** Ratings already saved in an earlier sitting. Read once, when the deck opens. */
  initialRatedCount?: number;
  /** Called after the pending answers are saved; `answered` counts this sitting's cards. */
  onFinish: (summary: { answered: number }) => void | Promise<unknown>;
  finishLabel?: string;
};

/**
 * The calibration card deck: one poster at a time with a 1–10 slider or "Haven't seen". Cards come in
 * batches from /me/calibration/next; each finished batch is submitted before the next one loads (so
 * the server can leave out what was answered). Finishing early submits the partial batch first.
 */
export function CalibrationDeck({
  deckId,
  minRated,
  skipAfter,
  initialRatedCount = 0,
  onFinish,
  finishLabel = 'Done',
}: Props) {
  const { colors } = useTheme();
  // Snapshot: submitting a batch also bumps the cached onboarding count this prop comes from, so
  // adding the live prop to this sitting's `rated` would count every submitted rating twice.
  const [startRated] = useState(initialRatedCount);
  const [batch, setBatch] = useState(0);
  const [index, setIndex] = useState(0);
  const [answers, setAnswers] = useState<CalibrationRatingDTO[]>([]);
  const [rated, setRated] = useState(0);
  const [seen, setSeen] = useState(0);
  const [score, setScore] = useState(START_SCORE);
  const [finishing, setFinishing] = useState(false);
  const [saveError, setSaveError] = useState<unknown>(null);
  const cards = useCalibrationBatch(deckId, batch);
  const submit = useSubmitCalibration();

  const list = cards.data ?? [];
  const current = list[index];
  const outOfCards = cards.isSuccess && list.length === 0;
  const totalRated = startRated + rated;
  const canFinish = totalRated >= minRated || outOfCards;
  const canSkip = !canFinish && skipAfter != null && seen >= skipAfter;

  /** Saves the pending answers; true when there was nothing to save or the save worked. */
  async function flush(pending: CalibrationRatingDTO[]): Promise<boolean> {
    if (pending.length === 0) return true;
    setSaveError(null);
    try {
      await submit.mutateAsync(pending);
    } catch (e) {
      setSaveError(e);
      return false;
    }
    setAnswers([]);
    return true;
  }

  async function answer(a: CalibrationRatingDTO) {
    const pending = [...answers, a];
    setAnswers(pending);
    setSeen((n) => n + 1);
    if (!a.unseen) setRated((n) => n + 1);
    setScore(START_SCORE);
    setIndex((i) => i + 1);
    // Last card of the batch: submit it, then deal the next batch.
    if (index + 1 >= list.length && (await flush(pending))) {
      setBatch((b) => b + 1);
      setIndex(0);
    }
  }

  async function retryBatch() {
    if (await flush(answers)) {
      setBatch((b) => b + 1);
      setIndex(0);
    }
  }

  async function finish() {
    setFinishing(true);
    try {
      if (await flush(answers)) await onFinish({ answered: seen });
    } finally {
      setFinishing(false);
    }
  }

  let card;
  if (cards.isPending) {
    card = <ActivityIndicator testID="calibration-loading" color={colors.accent} className="py-16" />;
  } else if (cards.isError) {
    card = (
      <ErrorState
        compact
        title="Couldn't load movies"
        error={cards.error}
        onRetry={() => void cards.refetch()}
        retrying={cards.isFetching}
      />
    );
  } else if (outOfCards) {
    card = (
      <Card testID="calibration-out" className="items-center gap-2 py-8">
        <Text className="font-display text-xl text-text">That’s every movie we have for now</Text>
        <Body muted className="text-center">
          You can always recalibrate later from your Taste editor.
        </Body>
      </Card>
    );
  } else if (!current) {
    // Past the batch's last card: it's being saved (or the save failed).
    card = saveError ? (
      <Card className="items-center gap-3 py-6">
        <Body accessibilityRole="alert" className="text-center text-danger">
          Couldn’t save those answers. {errorMessage(saveError)}
        </Body>
        <Button title="Try again" variant="secondary" onPress={() => void retryBatch()} />
      </Card>
    ) : (
      <ActivityIndicator testID="calibration-saving" color={colors.accent} className="py-16" />
    );
  } else {
    card = (
      <Card testID={`calibration-card-${current.id}`} className="items-center gap-4">
        <View className="aspect-[2/3] w-44 overflow-hidden rounded-2xl bg-raised">
          <View className="absolute inset-0 items-center justify-center p-2">
            <Text className="text-center font-display text-base text-muted">{current.title}</Text>
          </View>
          {current.posterUrl ? (
            <Image
              source={{ uri: current.posterUrl }}
              accessibilityLabel={`${current.title} poster`}
              style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
              contentFit="cover"
            />
          ) : null}
        </View>
        <View className="items-center">
          <Text testID="calibration-title" className="text-center font-display text-xl text-text">
            {current.title}
          </Text>
          {current.releaseYear ? <Body muted>{current.releaseYear}</Body> : null}
        </View>
        <Slider1to10
          className="w-full"
          value={score}
          onChange={setScore}
          accessibilityLabel={`Your score for ${current.title}`}
        />
        <View className="w-full flex-row gap-3">
          <Button
            title="Haven’t seen"
            variant="secondary"
            className="flex-1"
            onPress={() => void answer({ movieId: current.id, score: null, unseen: true })}
          />
          <Button
            title={`Rate ${score}`}
            className="flex-1"
            onPress={() => void answer({ movieId: current.id, score, unseen: false })}
          />
        </View>
      </Card>
    );
  }

  return (
    <View className="gap-4">
      <Text testID="calibration-progress" className="text-center font-body-medium text-sm text-muted">
        {minRated > 0 && totalRated < minRated
          ? `Rated ${totalRated} of ${minRated}`
          : `Rated ${totalRated}`}
      </Text>
      {card}
      {canFinish ? (
        <Button title={finishLabel} loading={finishing || submit.isPending} onPress={() => void finish()} />
      ) : canSkip ? (
        <Button title="Skip for now" variant="secondary" loading={finishing || submit.isPending} onPress={() => void finish()} />
      ) : null}
      {saveError && current ? (
        <Body testID="calibration-error" accessibilityRole="alert" className="text-center text-sm text-danger">
          Couldn’t save your answers. {errorMessage(saveError)}
        </Body>
      ) : null}
    </View>
  );
}
