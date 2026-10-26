import { useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { TextInput } from '../ui/TextInput';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { RATING_NOTE_MAX, useMyRating, useUpsertRating } from '../../api/ratings';
import { ApiError } from '../../lib/apiClient';
import { useTheme } from '../../theme/useTheme';
import type { MovieSummaryDTO, RatingDTO, RatingSource, RatingVisibility } from '../../types/api';
import { Body, Button, ChoiceGroup, errorMessage, ErrorState, Sheet, Toggle } from '../ui';
import { MoviePicker } from './MoviePicker';
import { ScoreTapRow } from './ScoreTapRow';

type FeedAudience = Exclude<RatingVisibility, 'PRIVATE'>;

const AUDIENCE_CHOICES: { value: FeedAudience; label: string }[] = [
  { value: 'FRIENDS', label: 'Friends' },
  { value: 'PUBLIC', label: 'Public' },
];

type FormProps = {
  movie: MovieSummaryDTO;
  existing: RatingDTO | null;
  source: RatingSource;
  onSaved: (rating: RatingDTO) => void;
  /** "Remove rating": the rating being removed (the caller hides it and owns the deferred DELETE). */
  onRemoved: (previous: RatingDTO) => void;
};

function QuickRateForm({ movie, existing, source, onSaved, onRemoved }: FormProps) {
  const { colors } = useTheme();
  const upsert = useUpsertRating();
  const [score, setScore] = useState<number | null>(existing?.score ?? null);
  const [note, setNote] = useState(existing?.body ?? '');
  const [spoilers, setSpoilers] = useState(existing?.containsSpoilers ?? false);
  const [noteOpen, setNoteOpen] = useState(!!existing?.body);
  // New ratings go to friends by default (the server's default too).
  const [postToFeed, setPostToFeed] = useState(existing ? existing.visibility !== 'PRIVATE' : true);
  const [audience, setAudience] = useState<FeedAudience>(existing?.visibility === 'PUBLIC' ? 'PUBLIC' : 'FRIENDS');
  const busy = upsert.isPending;

  const save = () => {
    if (score == null) return;
    const trimmed = note.trim();
    upsert.mutate(
      {
        movieId: movie.id,
        body: {
          score,
          body: trimmed ? trimmed : null,
          containsSpoilers: trimmed ? spoilers : false,
          visibility: postToFeed ? audience : 'PRIVATE',
          // Re-rating a calibration swipe turns it into a quick rate.
          source: existing && existing.source !== 'ONBOARDING_SWIPE' ? existing.source : source,
        },
      },
      { onSuccess: onSaved },
    );
  };

  // Removal is deferred (the provider hides it and offers Undo), so there is nothing to wait for here.
  const removeRating = () => {
    if (!existing) return;
    upsert.reset();
    onRemoved(existing);
  };

  const fieldErrors = upsert.error instanceof ApiError ? upsert.error.fieldErrors : {};
  const error = upsert.isError
    ? Object.keys(fieldErrors).length
      ? Object.values(fieldErrors).join(' ')
      : errorMessage(upsert.error)
    : null;

  return (
    <View className="gap-5">
      <View className="flex-row items-center gap-4">
        <View className="h-24 w-16 overflow-hidden rounded-xl bg-raised">
          {movie.posterUrl ? (
            <Image
              testID="quick-rate-poster"
              source={{ uri: movie.posterUrl }}
              accessibilityLabel={`${movie.title} poster`}
              style={{ width: '100%', height: '100%' }}
              contentFit="cover"
            />
          ) : (
            <View className="flex-1 items-center justify-center">
              <Ionicons name="film-outline" size={24} color={colors.muted} />
            </View>
          )}
        </View>
        <View className="flex-1 gap-0.5">
          <Text className="font-display text-xl text-text" numberOfLines={2}>
            {movie.title}
          </Text>
          {movie.releaseYear ? <Body muted className="text-sm">{movie.releaseYear}</Body> : null}
          {existing ? (
            <Body muted testID="quick-rate-existing" className="text-sm">
              You rated it {existing.score}/10
            </Body>
          ) : null}
        </View>
      </View>

      <ScoreTapRow value={score} onChange={setScore} disabled={busy} />

      {noteOpen ? (
        <View className="gap-2">
          <TextInput
            accessibilityLabel="Note"
            value={note}
            onChangeText={setNote}
            maxLength={RATING_NOTE_MAX}
            multiline
            placeholder="What did you think? (optional)"
            placeholderTextColor={colors.muted}
            className="min-h-24 rounded-xl bg-raised p-3 font-body text-base text-text"
            textAlignVertical="top"
          />
          <View className="flex-row items-center justify-between">
            <View className="flex-row items-center gap-2">
              <Toggle
                accessibilityLabel="Contains spoilers"
                value={spoilers}
                onValueChange={setSpoilers}
              />
              <Text className="font-body text-sm text-text">Contains spoilers</Text>
            </View>
            <Text testID="note-counter" className="font-body text-xs text-muted">
              {note.length}/{RATING_NOTE_MAX}
            </Text>
          </View>
        </View>
      ) : (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Add a note"
          onPress={() => setNoteOpen(true)}
          className="flex-row items-center gap-2 self-start active:opacity-70"
        >
          <Ionicons name="create-outline" size={18} color={colors.accent} />
          <Text className="font-body-medium text-base text-accent-ink">Add a note</Text>
        </Pressable>
      )}

      <View className="gap-3">
        <View className="flex-row items-center justify-between gap-4">
          <View className="flex-1">
            <Text className="font-body-semibold text-base text-text">Post to feed</Text>
            <Body muted className="text-sm">
              {postToFeed ? 'Shown in your feed.' : 'Only you can see this rating.'}
            </Body>
          </View>
          <Toggle
            accessibilityLabel="Post to feed"
            value={postToFeed}
            onValueChange={setPostToFeed}
          />
        </View>
        {postToFeed ? (
          <ChoiceGroup label="Who can see it" choices={AUDIENCE_CHOICES} value={audience} onChange={setAudience} />
        ) : null}
      </View>

      {error ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {error}
        </Body>
      ) : null}

      <Button
        title={existing ? 'Update rating' : 'Save rating'}
        loading={upsert.isPending}
        disabled={score == null}
        onPress={save}
      />
      {existing ? (
        <Button
          title="Remove rating"
          variant="ghost"
          disabled={upsert.isPending}
          onPress={removeRating}
        />
      ) : null}
    </View>
  );
}

/** Loads the caller's existing rating first, so re-rating starts from it. */
function QuickRateBody(props: Omit<FormProps, 'existing'>) {
  const { colors } = useTheme();
  const mine = useMyRating(props.movie.id);
  if (mine.isPending) return <ActivityIndicator testID="quick-rate-loading" color={colors.accent} className="py-10" />;
  if (mine.isError) {
    return (
      <ErrorState
        compact
        title="Couldn't load your rating"
        error={mine.error}
        onRetry={() => void mine.refetch()}
        retrying={mine.isFetching}
      />
    );
  }
  // Not keyed by the rating: saving rewrites the cached rating, and a remount would drop the save's callbacks.
  return <QuickRateForm existing={mine.data} {...props} />;
}

type Props = {
  visible: boolean;
  /** null = pick a movie first (movie search). */
  movie: MovieSummaryDTO | null;
  onPickMovie: (movie: MovieSummaryDTO) => void;
  onClose: () => void;
  onSaved: (rating: RatingDTO) => void;
  onRemoved: (previous: RatingDTO) => void;
  source?: RatingSource;
};

/**
 * The one quick-rate sheet: poster, a 1–10 tap row, an optional note (with spoiler flag), who sees
 * it, and Save / Remove. Without a movie it starts with a movie search. Open it through
 * `useQuickRate()` rather than rendering it directly.
 */
export function QuickRateSheet({ visible, movie, onPickMovie, onClose, onSaved, onRemoved, source = 'QUICK_RATE' }: Props) {
  return (
    <Sheet visible={visible} onClose={onClose} title={movie ? 'Quick rate' : 'Rate a movie'}>
      {!visible ? null : movie ? (
        <QuickRateBody key={movie.id} movie={movie} source={source} onSaved={onSaved} onRemoved={onRemoved} />
      ) : (
        <MoviePicker onPick={onPickMovie} />
      )}
    </Sheet>
  );
}
