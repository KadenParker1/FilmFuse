package com.pm.filmfuse.movie.seed;

import java.time.Duration;

/** Waits. {@link #SYSTEM} really sleeps; tests pass one that advances a fake clock instead. */
@FunctionalInterface
public interface Sleeper {

    Sleeper SYSTEM = duration -> {
        if (duration.isPositive()) {
            Thread.sleep(duration);
        }
    };

    void sleep(Duration duration) throws InterruptedException;
}
