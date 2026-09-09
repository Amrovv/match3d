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
 * A test instrument rather than a production caller. Workers spin rather than back
 * off, because the point is maximum contention on the shared structures, and
 * nothing here is synchronised, because we must to observe the race
 * before fixing it.
 *
 * Each worker collects into its own private list, so the harness shares no
 * mutable state and cannot lose or duplicate a lobby of its own accord. The
 * lists are merged on the calling thread after every worker has stopped, so
 * any race the test observes belongs to the engine.
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

    /**
     * One run of the pool: every lobby formed, and every exception a worker
     * survived.
     *
     * Both lists are merged from per worker lists after every worker has
     * stopped, so neither is written concurrently.
     */
    public record Run(List<Lobby> lobbies, List<RuntimeException> failures) {
    }

    /**
     * Starts every worker. Each spins on formLobby until stop is called.
     */
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
     * Stops every worker and returns everything the run produced, in no
     * particular order.
     *
     * awaitTermination is what makes the merge safe: it guarantees no worker is
     * still inside formLobby, and it establishes the happens before edge that
     * makes each worker's writes visible to the caller.
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
