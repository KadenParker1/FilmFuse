package com.pm.filmfuse.social.service;

import com.pm.filmfuse.social.repository.ClosenessSignalRepository;
import com.pm.filmfuse.social.repository.ClosenessSignalRepository.PairSignals;
import com.pm.filmfuse.social.repository.FriendshipRepository.ClosenessRow;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recomputes implicit closeness ({@code friend_closeness.score}) for every accepted friendship,
 * both directions, with {@link ClosenessFormula} and the weights in {@link ClosenessProperties}.
 * Scheduled daily by {@link ClosenessConfig} (off under the test profile; tests call
 * {@link #recomputeAll()}). The explicit close-friend flag is never touched. Single replica only.
 */
@Component
public class ClosenessJob {

    private static final Logger log = LoggerFactory.getLogger(ClosenessJob.class);

    private final ClosenessSignalRepository signals;
    private final SocialGraphService graph;
    private final ClosenessProperties properties;
    private final Clock clock;

    public ClosenessJob(ClosenessSignalRepository signals, SocialGraphService graph, ClosenessProperties properties,
                        Clock clock) {
        this.signals = signals;
        this.graph = graph;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return how many directed closeness rows were updated */
    public int recomputeAll() {
        List<PairSignals> pairs = signals.acceptedPairSignals(
                clock.instant().minus(Duration.ofDays(properties.windowDays())));
        List<ClosenessRow> scores = new ArrayList<>(pairs.size() * 2);
        for (PairSignals pair : pairs) {
            double taste = ClosenessFormula.tasteSimilarity(pair.lowVector(), pair.highVector(),
                    properties.neutralTaste());
            float score = (float) ClosenessFormula.score(properties, pair.coSessions(), taste);
            scores.add(new ClosenessRow(pair.pair().low(), pair.pair().high(), score));
            scores.add(new ClosenessRow(pair.pair().high(), pair.pair().low(), score));
        }
        int updated = graph.setImplicitCloseness(scores);
        log.info("Closeness: {} friendships, {} rows updated", pairs.size(), updated);
        return updated;
    }
}
