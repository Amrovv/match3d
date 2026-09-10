package com.match3d.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the matching engine does under load, across workloads that differ in how
 * many buckets a window covers.
 *
 * Reports numbers, asserts almost nothing. Excluded from the normal build by
 * its tag, since a timing measurement is not a regression gate. Run it with
 * ./gradlew :matchmaking-core:benchmark
 *
 * The question it exists to answer: claiming the anchor keeps a losing worker's
 * verified seats instead of discarding the whole selection, and that should pay
 * only when selecting is expensive relative to refilling. Selection cost grows
 * with the number of buckets in the window, so spread is the variable.
 */
@Tag("benchmark")
class MatchingBenchmark {

    /** Ratings are drawn from this many distinct values, centred on 1500. */
    private record Workload(String name, int players, int spread, int workers, int runMillis) {
    }

    private record Result(Workload workload, long elapsedMillis, int lobbies, int seated,
                          int duplicates, int leftQueued, int retries, int contention,
                          int starvation, int aborts) {
    }

    /**
     * Short enough that a cooled anchor returns inside the run.
     *
     * The default of ten seconds outlives any run this benchmark makes, so it
     * would measure the cooldown rather than the engine.
     */
    private static final Duration COOLDOWN = Duration.ofMillis(50);

    private static final List<Workload> WORKLOADS = List.of(
            new Workload("one bucket", 2000, 1, 8, 2000),
            new Workload("narrow, 20 ratings", 2000, 20, 8, 2000),
            new Workload("mid, 200 ratings", 2000, 200, 8, 2000),
            new Workload("wide, 1000 ratings", 2000, 1000, 8, 2000),
            new Workload("wide, few players", 400, 1000, 8, 2000),
            new Workload("wide, sixteen workers", 2000, 1000, 16, 2000));

    @Test void reportAcrossWorkloads() throws InterruptedException {
        List<Result> results = new ArrayList<>();
        for (Workload workload : WORKLOADS) {
            results.add(run(workload));
        }

        System.out.println();
        System.out.printf("%-24s %7s %7s %7s %6s %7s %8s %8s %8s %7s%n",
                "workload", "players", "ms", "lobbies", "dup", "queued",
                "retries", "content.", "starved", "aborts");
        for (Result r : results) {
            System.out.printf("%-24s %7d %7d %7d %6d %7d %8d %8d %8d %7d%n",
                    r.workload().name(), r.workload().players(), r.elapsedMillis(),
                    r.lobbies(), r.duplicates(), r.leftQueued(), r.retries(),
                    r.contention(), r.starvation(), r.aborts());
        }
        System.out.println();
    }

    /**
     * One run against a fresh engine.
     *
     * Ratings are spread evenly rather than clustered normally, which
     * overstates how many buckets a real window would cover and is deliberate:
     * bucket count is the variable under test.
     */
    private Result run(Workload workload) throws InterruptedException {
        SkillIndex index = new SkillIndex();
        FairnessHeap heap = new FairnessHeap();
        MatchMaker matcher = new MatchMaker(index, heap, COOLDOWN);

        Instant now = Instant.now();
        int lowest = 1500 - workload.spread() / 2;
        for (int i = 0; i < workload.players(); i++) {
            Player player = new Player(UUID.randomUUID(),
                    lowest + (i % workload.spread()),
                    now.minusSeconds(workload.players() - i));
            index.insert(player);
            heap.insert(player);
        }

        MatchingWorkerPool pool = new MatchingWorkerPool(matcher, workload.workers());
        long started = System.currentTimeMillis();
        pool.start();
        Thread.sleep(workload.runMillis());
        MatchingWorkerPool.Run run = pool.stop();
        long elapsed = System.currentTimeMillis() - started;

        List<Player> seated = run.lobbies().stream().flatMap(lobby -> lobby.members().stream()).toList();
        int distinct = (int) seated.stream().distinct().count();
        int queued = (int) index.playersInRange(1, 5000).flatMap(Set::stream).count();

        return new Result(workload, elapsed, run.lobbies().size(), seated.size(),
                seated.size() - distinct, queued, matcher.retryCount(),
                matcher.contentionCount(), matcher.starvationCount(), matcher.abortCount());
    }
}
