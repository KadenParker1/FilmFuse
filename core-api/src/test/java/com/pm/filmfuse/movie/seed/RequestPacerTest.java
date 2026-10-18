package com.pm.filmfuse.movie.seed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RequestPacer on a fake clock: requests are spaced 1/rate apart, never bursting, never sleeping for real. */
class RequestPacerTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper fakeSleep = duration -> {
        sleeps.add(duration);
        nanos.addAndGet(duration.toNanos());
    };

    @Test
    void theFirstRequestGoesAtOnceAndTheNextWaitForTheirSlot() throws InterruptedException {
        RequestPacer pacer = new RequestPacer(20, nanos::get, fakeSleep);
        long start = nanos.get();

        for (int i = 0; i < 5; i++) {
            pacer.acquire();
        }

        assertThat(pacer.interval()).isEqualTo(Duration.ofMillis(50));
        assertThat(sleeps).hasSize(4).allMatch(d -> d.equals(Duration.ofMillis(50)));
        assertThat(nanos.get() - start).isEqualTo(Duration.ofMillis(200).toNanos());
    }

    @Test
    void timeAlreadySpentElsewhereCountsTowardsTheWait() throws InterruptedException {
        RequestPacer pacer = new RequestPacer(10, nanos::get, fakeSleep);
        pacer.acquire();
        nanos.addAndGet(Duration.ofMillis(30).toNanos());

        pacer.acquire();

        assertThat(sleeps).containsExactly(Duration.ofMillis(70));
    }

    @Test
    void aSlowCallerNeverSleepsAndIdleTimeIsNotBankedAsABurst() throws InterruptedException {
        RequestPacer pacer = new RequestPacer(10, nanos::get, fakeSleep);
        pacer.acquire();
        nanos.addAndGet(Duration.ofSeconds(5).toNanos());

        pacer.acquire();
        pacer.acquire();

        assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void aFractionalRateIsSupportedAndANonPositiveOneRefused() throws InterruptedException {
        RequestPacer pacer = new RequestPacer(0.5, nanos::get, fakeSleep);
        pacer.acquire();
        pacer.acquire();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));

        assertThatThrownBy(() -> new RequestPacer(0, nanos::get, fakeSleep)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestPacer(Double.NaN, nanos::get, fakeSleep))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSystemPacerReallyWaits() throws InterruptedException {
        RequestPacer pacer = RequestPacer.system(100);
        long start = System.nanoTime();
        pacer.acquire();
        pacer.acquire();
        pacer.acquire();
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(19).toNanos());
    }
}
