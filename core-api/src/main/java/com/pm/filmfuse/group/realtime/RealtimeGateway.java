package com.pm.filmfuse.group.realtime;

import java.util.Map;

/**
 * Pushes broadcast messages to Supabase Realtime. {@link SupabaseRealtimeGateway} when the
 * service-role key is set, {@link MockRealtimeGateway} otherwise (local dev, tests). Messages are
 * always sent as <em>private</em> channel messages: only users allowed by the RLS policy on
 * {@code realtime.messages} (supabase/migrations) receive them.
 */
public interface RealtimeGateway {

    /** Throws on failure; callers decide whether that matters. */
    void broadcast(String topic, String event, Map<String, Object> payload);
}
