import type { RealtimeChannel } from '@supabase/supabase-js';
import { supabase } from './supabase';

/**
 * Supabase Realtime for live session voting. core-api broadcasts `session_updated` on the private
 * topic `session:{id}` after every vote / refresh / pick; the payload carries no data, so clients
 * just refetch. RLS on `realtime.messages` only lets session members join.
 */

export const SESSION_UPDATED_EVENT = 'session_updated';
/** Not SUBSCRIBED by then → fall back to polling (the channel may still join later). */
export const SUBSCRIBE_TIMEOUT_MS = 3_000;
/** Polling interval while the channel is down. */
export const POLL_INTERVAL_MS = 5_000;

export type SessionUpdateType = 'vote' | 'refresh' | 'pick';

export type SessionUpdatedPayload = { sessionId: string; type: SessionUpdateType };

/** `connecting` until the channel joins; `live` once SUBSCRIBED; `polling` after an error or timeout. */
export type LiveMode = 'connecting' | 'live' | 'polling';

export const sessionTopic = (sessionId: string) => `session:${sessionId}`;

type Handlers = {
  onUpdate: (payload: Partial<SessionUpdatedPayload>) => void;
  onMode: (mode: LiveMode) => void;
};

/**
 * Joins the session's private broadcast channel. Returns an unsubscribe function that is safe to
 * call before the (async) join has even started.
 */
export function subscribeToSession(sessionId: string, { onUpdate, onMode }: Handlers): () => void {
  let closed = false;
  let channel: RealtimeChannel | null = null;
  const timer = setTimeout(() => {
    if (!closed) onMode('polling');
  }, SUBSCRIBE_TIMEOUT_MS);

  onMode('connecting');

  const join = async () => {
    try {
      // Private channels authorise with the user's JWT; no argument = the current session's token.
      await supabase.realtime.setAuth();
    } catch {
      // Join anyway: the server rejects it (CHANNEL_ERROR) and we fall back to polling.
    }
    if (closed) return;
    channel = supabase
      .channel(sessionTopic(sessionId), { config: { private: true } })
      .on('broadcast', { event: SESSION_UPDATED_EVENT }, (message: { payload?: Partial<SessionUpdatedPayload> }) => {
        if (!closed) onUpdate(message.payload ?? {});
      })
      .subscribe((status) => {
        if (closed) return;
        if (status === 'SUBSCRIBED') {
          clearTimeout(timer);
          onMode('live');
        } else if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED') {
          clearTimeout(timer);
          onMode('polling');
        }
      });
  };
  void join();

  return () => {
    closed = true;
    clearTimeout(timer);
    if (channel) void supabase.removeChannel(channel);
  };
}
