package com.pm.filmfuse.group.realtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used when {@code filmfuse.supabase.service-role-key} is blank. Sends nothing; records each broadcast. */
public class MockRealtimeGateway implements RealtimeGateway {

    private static final Logger log = LoggerFactory.getLogger(MockRealtimeGateway.class);

    public record Broadcast(String topic, String event, Map<String, Object> payload) {
    }

    private final List<Broadcast> sent = new CopyOnWriteArrayList<>();

    @Override
    public void broadcast(String topic, String event, Map<String, Object> payload) {
        sent.add(new Broadcast(topic, event, Map.copyOf(payload)));
        log.info("[mock realtime] would broadcast {} to {}: {}", event, topic, payload);
    }

    public List<Broadcast> sent() {
        return List.copyOf(sent);
    }
}
