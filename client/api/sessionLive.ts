import { useCallback, useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useFocusEffect } from 'expo-router';
import { ApiError } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { POLL_INTERVAL_MS, subscribeToSession, type LiveMode } from '../lib/realtime';
import { useSession } from './sessions';

/**
 * Keeps a session screen current. While the session is OPEN it listens on the realtime channel and
 * refetches on every `session_updated`; if the channel can't join (error, timeout, closed) it polls
 * every few seconds instead — only while the screen is focused. Returns how it's staying current.
 */
export function useSessionLive(sessionId: string | undefined): { mode: LiveMode } {
  const qc = useQueryClient();
  const session = useSession(sessionId);
  const status = session.data?.status;
  // Before the first load we don't know the status yet; listen optimistically.
  const open = status === undefined || status === 'OPEN';
  // A group member who isn't in the session yet (403, "Join") can't join the private channel: RLS
  // only lets members in. Wait until they join - the session then loads - and subscribe afresh,
  // rather than staying on the polling fallback from the rejected attempt.
  const notMember = !session.data && session.error instanceof ApiError && session.error.status === 403;
  const [mode, setMode] = useState<LiveMode>('connecting');

  useEffect(() => {
    if (!sessionId || !open || notMember) return;
    return subscribeToSession(sessionId, {
      onMode: setMode,
      onUpdate: (payload) => {
        void qc.invalidateQueries({ queryKey: queryKeys.session(sessionId) });
        // Votes move "Live now" counts and the Groups badge; picks, early ends and new members also
        // move history lists and the group's sessions.
        if (payload.type !== 'refresh') void qc.invalidateQueries({ queryKey: queryKeys.sessionListAll });
        if (payload.type === 'pick' || payload.type === 'ended' || payload.type === 'members') {
          void qc.invalidateQueries({ queryKey: queryKeys.groups });
        }
      },
    });
  }, [qc, sessionId, open, notMember]);

  const polling = !!sessionId && status === 'OPEN' && mode === 'polling';
  useFocusEffect(
    useCallback(() => {
      if (!polling || !sessionId) return;
      const timer = setInterval(() => {
        void qc.invalidateQueries({ queryKey: queryKeys.session(sessionId) });
      }, POLL_INTERVAL_MS);
      return () => clearInterval(timer);
    }, [qc, sessionId, polling]),
  );

  return { mode };
}
