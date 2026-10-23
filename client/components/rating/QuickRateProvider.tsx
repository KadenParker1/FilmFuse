import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { View } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { commitRatingRemoval, hideRatingForRemoval, undoRatingRemoval } from '../../api/ratings';
import { useTasteProfile } from '../../api/taste';
import { queryKeys } from '../../lib/queryKeys';
import type { MovieSummaryDTO, RatingDTO, RatingSource } from '../../types/api';
import { Toast, UndoToastProvider, useUndoToast } from '../ui';
import { QuickRateSheet } from './QuickRateSheet';

/** How long "Taste updated" stays up. */
export const TOAST_HIDE_MS = 2_500;
/** Stop waiting for the rebuild after this long (the scheduler may be down); the toast just goes away. */
export const TASTE_WAIT_MAX_MS = 60_000;

export const TASTE_UPDATING = 'Taste updating…';
export const TASTE_UPDATED = 'Taste updated';
export const RATING_REMOVED = 'Rating removed';
export const RATING_REMOVE_FAILED = 'Couldn’t remove rating';
/** How far the taste toast moves up while an undo toast sits in its place. */
const STACKED_TOAST_OFFSET = 56;

type QuickRateApi = {
  /** Opens the sheet on one movie (movie detail, "Already seen"). */
  openQuickRate: (movie: MovieSummaryDTO, options?: { source?: RatingSource }) => void;
  /** Opens the sheet on a movie search first ("+ Rate"). */
  openRatePicker: () => void;
  /** Shows "Taste updating…" until the taste profile is FRESH again, then "Taste updated". */
  showTasteUpdating: () => void;
};

const QuickRateContext = createContext<QuickRateApi | null>(null);

export function useQuickRate(): QuickRateApi {
  const api = useContext(QuickRateContext);
  if (!api) throw new Error('useQuickRate must be used inside <QuickRateProvider>');
  return api;
}

type SheetState = { open: boolean; movie: MovieSummaryDTO | null; source: RatingSource };

/** Watches the taste profile after a taste signal and drives the toast. */
function useTasteToast() {
  const qc = useQueryClient();
  // When the last taste signal was sent; null = no toast.
  const [since, setSince] = useState<number | null>(null);
  const taste = useTasteProfile({ enabled: since != null, pollWhileNone: true });
  // Only an answer fetched after the save counts; the cache may still hold the old FRESH profile.
  const updated = since != null && taste.data?.status === 'FRESH' && taste.dataUpdatedAt >= since;

  useEffect(() => {
    if (since == null) return;
    // A new vector means new picks.
    if (updated) void qc.invalidateQueries({ queryKey: queryKeys.recommendations });
    const t = setTimeout(() => setSince(null), updated ? TOAST_HIDE_MS : TASTE_WAIT_MAX_MS);
    return () => clearTimeout(t);
  }, [since, updated, qc]);

  const start = useCallback(() => setSince(Date.now()), []);
  const message = since == null ? null : updated ? TASTE_UPDATED : TASTE_UPDATING;
  return { start, message, tone: updated ? ('success' as const) : ('busy' as const) };
}

/**
 * The global quick-rate controller: one sheet for the whole app plus the "Taste updated" toast, and
 * the app's undo toast (`useUndoToast()`), which everything below it shares.
 * Mounted once at the root; anything opens it through `useQuickRate()`.
 */
export function QuickRateProvider({ children }: { children: ReactNode }) {
  return (
    <UndoToastProvider>
      <QuickRateController>{children}</QuickRateController>
    </UndoToastProvider>
  );
}

function QuickRateController({ children }: { children: ReactNode }) {
  const [sheet, setSheet] = useState<SheetState>({ open: false, movie: null, source: 'QUICK_RATE' });
  const toast = useTasteToast();
  const startToast = toast.start;
  const qc = useQueryClient();
  const undoToast = useUndoToast();
  const { showUndo, hideUndo, showError } = undoToast;
  // The "Rating removed" toast up right now (to take it down if that movie is re-rated meanwhile).
  const removalToast = useRef<{ key: number; movieId: number } | null>(null);

  const close = useCallback(() => setSheet((s) => ({ ...s, open: false })), []);
  const finished = useCallback(
    (rating: RatingDTO) => {
      setSheet((s) => ({ ...s, open: false }));
      startToast();
      // Re-rating dropped that movie's pending removal (useUpsertRating); its Undo has nothing left to do.
      if (removalToast.current?.movieId === rating.movie.id) hideUndo(removalToast.current.key);
    },
    [startToast, hideUndo],
  );
  /**
   * "Remove rating" → hidden at once, "Rating removed · Undo". The DELETE only goes out when the undo
   * window closes; Undo just shows the rating again (same row, id, likes, comments).
   */
  const removed = useCallback(
    (previous: RatingDTO) => {
      const movieId = previous.movie.id;
      setSheet((s) => ({ ...s, open: false }));
      hideRatingForRemoval(qc, previous);
      const key = showUndo({
        message: RATING_REMOVED,
        undoAccessibilityLabel: `Undo removing your rating of ${previous.movie.title}`,
        onUndo: () => {
          undoRatingRemoval(qc, movieId);
        },
        onCommit: (reason) => {
          void commitRatingRemoval(qc, movieId, {
            keepalive: reason === 'background' || reason === 'unmount',
            onSend: startToast,
          }).catch(() => showError(RATING_REMOVE_FAILED));
        },
      });
      removalToast.current = { key, movieId };
    },
    [qc, showUndo, showError, startToast],
  );

  const api = useMemo<QuickRateApi>(
    () => ({
      openQuickRate: (movie, options) => setSheet({ open: true, movie, source: options?.source ?? 'QUICK_RATE' }),
      openRatePicker: () => setSheet({ open: true, movie: null, source: 'QUICK_RATE' }),
      showTasteUpdating: startToast,
    }),
    [startToast],
  );

  return (
    <QuickRateContext.Provider value={api}>
      <View className="flex-1">
        {children}
        <Toast
          message={toast.message}
          tone={toast.tone}
          bottomOffset={undoToast.visible ? 96 + STACKED_TOAST_OFFSET : 96}
        />
      </View>
      <QuickRateSheet
        visible={sheet.open}
        movie={sheet.movie}
        source={sheet.source}
        onPickMovie={(movie) => setSheet((s) => ({ ...s, movie }))}
        onClose={close}
        onSaved={finished}
        onRemoved={removed}
      />
    </QuickRateContext.Provider>
  );
}
