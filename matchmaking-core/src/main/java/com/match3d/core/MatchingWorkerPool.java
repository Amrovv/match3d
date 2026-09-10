package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Several threads running the matching pass against one shared engine.
 *
 * A test instrument, not a production caller. Workers spin rather than back
 * off, since the point is maximum contention. Nothing here is synchronised:
 * safety belongs to the engine, not to the harness driving it.
 *
 * Each worker collects into a private list, merged on the calling thread once
 * every worker has stopped, so any race observed belongs to the engine.
 */
public final class MatchingWorkerPool {

    private final MatchMaker matcher;
    private final int workerCount;
    private final ExecutorService threads;
    private final List<List<Lobby>> collected = new ArrayList<>();
    private final List<List<RuntimeException>> failures = new ArrayList<>();

    private volatile boolean running = false;

    public MatchingWorkerPool(MatchMaker matcher, int workerCount) {
        this.matcher = matcher;
        this.workerCount = workerCount;
        this.threads = Executors.newFixedThreadPool(workerCount);
    }

    /** Every lobby formed, and every exception a worker survived. */
    public record Run(List<Lobby> lobbies, List<RuntimeException> failures) {
    }

    /** Starts every worker. Each spins on formLobby until stop is called. */
    public void start() {
        running = true;
        for (int i = 0; i < workerCount; i++) {
            List<Lobby> myLobbies = new ArrayList<>();
            List<RuntimeException> myFailures = new ArrayList<>();
            collected.add(myLobbies);
            failures.add(myFailures);

            threads.submit(() -> {
                while (running) {
                    try {
                        matcher.formLobby(Instant.now()).ifPresent(myLobbies::add);
                    } catch (RuntimeException e) {
                        myFailures.add(e);
                    }
                }
            });
        }
    }

    /**
     * Stops every worker and returns the run, in no particular order.
     *
     * awaitTermination makes the merge safe: no worker is still inside
     * formLobby, and it gives the happens before edge for their writes.
     */
    public Run stop() throws InterruptedException {
        running = false;
        threads.shutdown();
        if (!threads.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("workers did not stop within 10 seconds");
        }

        return new Run(
            collected.stream().flatMap(List::stream).toList(),
            failures.stream().flatMap(List::stream).toList()
        );

    }
}
