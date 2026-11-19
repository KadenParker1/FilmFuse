import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { useSaveFavorites, useSaveLeastFavorites } from '../../api/onboarding';
import type { FavoriteMovieDTO, MovieSummaryDTO, OnboardingStateDTO } from '../../types/api';
import { Body, Button, Heading, Label } from '../ui';
import { MoviePicker } from './MoviePicker';
import { OnboardingFrame } from './OnboardingFrame';
import { describeError, FAVORITES_MAX, FAVORITES_MIN, LEAST_FAVORITES_MAX } from './steps';
import { useAdvance } from './useAdvance';

export { moveItem } from './MoviePicker';

export type FavoritesWrite = { list: 'favorites' | 'leastFavorites'; movieIds: number[] };

const byRank = (list: FavoriteMovieDTO[]) => [...list].sort((a, b) => a.rank - b.rank).map((f) => f.movie);
const sameOrder = (a: number[], b: number[]) => a.length === b.length && a.every((id, i) => id === b[i]);

/**
 * The PUTs that take the server from the saved lists to the new ones, in an order it accepts: it
 * refuses a write that puts a movie in both lists, so when a saved least favourite became a
 * favourite, the least favourites go first (minus anything still saved as a favourite, which is
 * re-added after the favourites are saved). Least favourites are only sent when they changed.
 */
export function planFavoritesSave(
  favorites: number[],
  leastFavorites: number[],
  savedFavorites: number[],
  savedLeastFavorites: number[],
): FavoritesWrite[] {
  const fav: FavoritesWrite = { list: 'favorites', movieIds: favorites };
  const savedLeast = new Set(savedLeastFavorites);
  if (!favorites.some((id) => savedLeast.has(id))) {
    return sameOrder(leastFavorites, savedLeastFavorites) ? [fav] : [fav, { list: 'leastFavorites', movieIds: leastFavorites }];
  }
  const savedFav = new Set(savedFavorites);
  const interim = leastFavorites.filter((id) => !savedFav.has(id));
  const writes: FavoritesWrite[] = [{ list: 'leastFavorites', movieIds: interim }, fav];
  if (interim.length !== leastFavorites.length) writes.push({ list: 'leastFavorites', movieIds: leastFavorites });
  return writes;
}

const blockedBy = (list: MovieSummaryDTO[], reason: string) => new Map(list.map((m) => [m.id, reason]));

/**
 * Step 3: search (or browse popular) and pick 3–10 all-time favourites, ranked with up / down, plus
 * an optional 0–5 movies the user couldn't stand. A movie can only be in one of the two lists.
 */
export function FavoritesStep({ state }: { state: OnboardingStateDTO }) {
  const saveFavorites = useSaveFavorites();
  const saveLeast = useSaveLeastFavorites();
  const nav = useAdvance(3);
  const [picks, setPicks] = useState<MovieSummaryDTO[]>(() => byRank(state.favorites));
  const [leastPicks, setLeastPicks] = useState<MovieSummaryDTO[]>(() => byRank(state.leastFavorites));
  const [showLeast, setShowLeast] = useState(() => state.leastFavorites.length > 0);
  const inLeast = useMemo(() => blockedBy(leastPicks, 'In least favourites'), [leastPicks]);
  const inFavorites = useMemo(() => blockedBy(picks, 'In favourites'), [picks]);

  const clearErrors = () => {
    saveFavorites.reset();
    saveLeast.reset();
  };

  async function next() {
    nav.reset();
    clearErrors();
    const writes = planFavoritesSave(
      picks.map((m) => m.id),
      leastPicks.map((m) => m.id),
      byRank(state.favorites).map((m) => m.id),
      byRank(state.leastFavorites).map((m) => m.id),
    );
    try {
      for (const w of writes) {
        const body = w.movieIds.map((movieId, i) => ({ movieId, rank: i + 1 }));
        await (w.list === 'favorites' ? saveFavorites : saveLeast).mutateAsync(body);
      }
    } catch {
      return;
    }
    await nav.advance();
  }

  const remaining = FAVORITES_MIN - picks.length;
  const hint =
    remaining > 0
      ? `Pick ${remaining} more to continue.`
      : picks.length >= FAVORITES_MAX
        ? `That’s the maximum of ${FAVORITES_MAX}. Remove one to swap it.`
        : null;

  return (
    <OnboardingFrame
      step={3}
      title="All-time favourites"
      subtitle={`Pick ${FAVORITES_MIN} to ${FAVORITES_MAX} movies you’d happily watch again. Put your very favourite first.`}
      onBack={nav.back}
      onContinue={() => void next()}
      continueDisabled={picks.length < FAVORITES_MIN}
      loading={saveFavorites.isPending || saveLeast.isPending || nav.isPending}
      hint={hint}
      error={describeError(saveFavorites.error ?? saveLeast.error ?? nav.error)}
    >
      <MoviePicker
        picks={picks}
        setPicks={setPicks}
        max={FAVORITES_MAX}
        listName="favourites"
        testIDPrefix="favorite"
        searchLabel="Search favourite movies"
        emptyPrompt="Search for a movie you love."
        blocked={inLeast}
        onEdit={clearErrors}
      />
      <View testID="least-favorites-section" className="gap-3 border-t border-border pt-6">
        <View className="gap-1">
          <Heading size="sm">Any movies you couldn’t stand?</Heading>
          <Body muted>
            Optional, up to {LEAST_FAVORITES_MAX}. It helps us steer away from what you don’t like. Put the one you
            liked least first.
          </Body>
        </View>
        {showLeast ? (
          <>
            {leastPicks.length >= LEAST_FAVORITES_MAX ? (
              <Label testID="least-favorites-hint">
                That’s the maximum of {LEAST_FAVORITES_MAX}. Remove one to swap it.
              </Label>
            ) : null}
            <MoviePicker
              picks={leastPicks}
              setPicks={setLeastPicks}
              max={LEAST_FAVORITES_MAX}
              listName="least favourites"
              testIDPrefix="least-favorite"
              searchLabel="Search movies you couldn’t stand"
              emptyPrompt="Search for a movie you couldn’t stand."
              pickLabel={(m) => `Couldn’t stand ${m.releaseYear ? `${m.title} (${m.releaseYear})` : m.title}`}
              blocked={inFavorites}
              onEdit={clearErrors}
            />
          </>
        ) : (
          <Button variant="secondary" title="Add movies you couldn’t stand" onPress={() => setShowLeast(true)} />
        )}
      </View>
    </OnboardingFrame>
  );
}
