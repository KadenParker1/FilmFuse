package com.pm.filmfuse.support;

import com.pm.filmfuse.common.jdbc.SessionAdvisoryLock;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import java.util.Optional;
import java.util.function.Supplier;

/** An in-memory {@link CatalogJobLock} for unit tests: free (runs the work) unless {@link #held} is set. */
public class FakeCatalogJobLock extends CatalogJobLock {

    /** Someone else holds the lock: work does not run. */
    public volatile boolean held;
    /** How many times work ran under the lock. */
    public int acquired;

    public FakeCatalogJobLock() {
        super(new SessionAdvisoryLock(null, KEY));
    }

    public static FakeCatalogJobLock heldElsewhere() {
        FakeCatalogJobLock lock = new FakeCatalogJobLock();
        lock.held = true;
        return lock;
    }

    @Override
    public <T> Optional<T> tryRun(Supplier<T> work) {
        if (held) {
            return Optional.empty();
        }
        acquired++;
        return Optional.ofNullable(work.get());
    }

    @Override
    public boolean isHeld() {
        return held;
    }
}
