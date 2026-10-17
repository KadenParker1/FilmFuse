import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { View } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { useTasteProfile } from '../../api/taste';
import { queryKeys } from '../../lib/queryKeys';
import type { MovieSummaryDTO, RatingSource } from '../../types/api';
import { Toast } from '../ui';
import { QuickRateSheet } from './QuickRateSheet';

/** How long "Taste updated" stays up. */
export const TOAST_HIDE_MS = 2_500;
/** Stop waiting for the rebuild after this long (the scheduler may be down); the toast just goes away. */
export const TASTE_WAIT_MAX_MS = 60_000;

export const TASTE_UPDATING = 'Taste updating…';
export const TASTE_UPDATED = 'Taste updated';

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
 * The global quick-rate controller: one sheet for the whole app plus the "Taste updated" toast.
 * Mounted once at the root; anything opens it through `useQuickRate()`.
 */
export function QuickRateProvider({ children }: { children: ReactNode }) {
  const [sheet, setSheet] = useState<SheetState>({ open: false, movie: null, source: 'QUICK_RATE' });
  const toast = useTasteToast();
  const startToast = toast.start;

  const close = useCallback(() => setSheet((s) => ({ ...s, open: false })), []);
  const finished = useCallback(() => {
    setSheet((s) => ({ ...s, open: false }));
    startToast();
  }, [startToast]);

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
        <Toast message={toast.message} tone={toast.tone} />
      </View>
      <QuickRateSheet
        visible={sheet.open}
        movie={sheet.movie}
        source={sheet.source}
        onPickMovie={(movie) => setSheet((s) => ({ ...s, movie }))}
        onClose={close}
        onSaved={finished}
        onRemoved={finished}
      />
    </QuickRateContext.Provider>
  );
}
