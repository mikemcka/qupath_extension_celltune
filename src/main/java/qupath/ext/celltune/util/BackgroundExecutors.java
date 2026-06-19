package qupath.ext.celltune.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Factory for the daemon-threaded background executors used across the
 * extension's UI. Centralises the previously-repeated "named daemon thread
 * factory" boilerplate so every background pool is consistently named (for
 * thread dumps) and marked daemon (so a stray pool never blocks JVM shutdown).
 * <p>
 * Callers own the returned {@link ExecutorService} and remain responsible for
 * shutting it down.
 */
public final class BackgroundExecutors {

    private BackgroundExecutors() {} // utility class

    /**
     * A {@link ThreadFactory} producing daemon threads with the given base name.
     * Threads are named {@code <baseName>} (single) or, for pools, the base name
     * is reused for every worker — matching the prior per-class behaviour.
     */
    public static ThreadFactory daemonThreadFactory(String baseName) {
        return r -> {
            Thread t = new Thread(r, baseName);
            t.setDaemon(true);
            return t;
        };
    }

    /** Single-threaded executor backed by one daemon thread named {@code name}. */
    public static ExecutorService newSingleThread(String name) {
        return Executors.newSingleThreadExecutor(daemonThreadFactory(name));
    }

    /** Fixed-size pool of {@code nThreads} daemon threads named {@code name}. */
    public static ExecutorService newFixedPool(int nThreads, String name) {
        return Executors.newFixedThreadPool(nThreads, daemonThreadFactory(name));
    }
}
