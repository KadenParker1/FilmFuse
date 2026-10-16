package com.pm.filmfuse.group.realtime;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the session's members' clients to refetch: topic {@code session:<id>}, event
 * {@code session_updated}, payload {@code {sessionId, type}} - no session data, the client calls
 * {@code GET /sessions/{id}}. Sent only after the change commits (a rolled-back vote broadcasts
 * nothing); a failure is logged and swallowed, since the write already succeeded and clients also
 * poll.
 */
@Component
public class SessionRealtimeNotifier {

    private static final Logger log = LoggerFactory.getLogger(SessionRealtimeNotifier.class);

    public static final String EVENT = "session_updated";

    private final RealtimeGateway gateway;

    public SessionRealtimeNotifier(RealtimeGateway gateway) {
        this.gateway = gateway;
    }

    public static String topicOf(UUID sessionId) {
        return "session:" + sessionId;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionUpdated(SessionUpdatedEvent event) {
        try {
            gateway.broadcast(topicOf(event.sessionId()), EVENT,
                    Map.of("sessionId", event.sessionId().toString(), "type", event.type()));
        } catch (RuntimeException ex) {
            log.warn("Could not broadcast {} for session {}", event.type(), event.sessionId(), ex);
        }
    }
}
