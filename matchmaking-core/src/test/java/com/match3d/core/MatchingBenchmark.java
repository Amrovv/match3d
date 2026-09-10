package com.match3d.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the matching engine does under load, across workloads that differ in
 * population, rating spread and worker count.
 *
 * Reports numbers, asserts nothing. Excluded from the normal build by its tag,
 * since a timing measurement is not a regression gate. Run it with
 * ./gradlew :matchmaking-core:benchmark
 *
 * Populations are large enough that most workloads never drain inside the run,
 * so the numbers describe a queue in steady state rather than the tail of one
 * emptying out. The small workload is kept precisely because it does drain, and
 * the two behave differently.
 */
@Tag("benchmark")
class MatchingBenchmark {

    /** Ratings are normal around 1500 when sigma is positive, uniform otherwise. */
    private record Workload(String name, int players, int sigma, int uniformWidth,
                            int workers, int runMillis, int repeats) {
    }

    private record Result(int lobbies, int duplicates, int leftQueued, int retries,
                          int contention, int starvation, int aborts, long elapsedMillis) {
    }

    /** Short enough that a cooled anchor returns many times over inside a run. */
    private static final Duration COOLDOWN = Duration.ofMillis(50);

    /** Fixed, so both sides of a comparison see the same population. */
    private static final long SEED = 20260910L;

    private static final int RUN_MILLIS = 3000;
    private static final int REPEATS = 3;

    private static final List<Workload> WORKLOADS = List.of(
            new Workload("20k, single rating", 20000, 0, 1, 8, RUN_MILLIS, REPEATS),
            new Workload("20k, normal s=100", 20000, 100, 0, 8, RUN_MILLIS, REPEATS),
            new Workload("20k, normal s=400", 20000, 400, 0, 8, RUN_MILLIS, REPEATS),
            new Workload("20k, uniform 1..5000", 20000, 0, 5000, 8, RUN_MILLIS, REPEATS),
            new Workload("20k, normal s=400, 16w", 20000, 400, 0, 16, RUN_MILLIS, REPEATS),
            new Workload("2k, normal s=400", 2000, 400, 0, 8, RUN_MILLIS, REPEATS));

    @Test void reportAcrossWorkloads() throws InterruptedException {
        System.out.printf("%n%-24s %8s %8s %7s %8s %9s %9s %8s%n",
                "workload", "lobbies", "seated", "queued", "retries", "contention",
                "starved", "aborts");

        for (Workload workload : WORKLOADS) {
            List<Result> runs = new ArrayList<>();
            for (int i = 0; i < workload.repeats(); i++) {
                runs.add(run(workload));
            }
            System.out.printf("%-24s %8d %8d %7d %8d %9d %9d %8d%n",
                    workload.name(),
                    mean(runs, Result::lobbies),
                    mean(runs, Result::lobbies) * MatchMaker.LOBBY_SIZE,
                    mean(runs, Result::leftQueued),
                    mean(runs, Result::retries),
                    mean(runs, Result::contention),
                    mean(runs, Result::starvation),
                    mean(runs, Result::aborts));

            int duplicates = runs.stream().mapToInt(Result::duplicates).sum();
            if (duplicates != 0) {
                System.out.println("  DUPLICATES SEATED: " + duplicates);
            }
        }
        System.out.println();
    }

    /**
     * How fast lobbies form, rather than whether they eventually all do.
     *
     * The workload sweep above runs long enough for every population to drain,
     * so both variants finish the queue and the comparison has no room to
     * separate them. These runs are deliberately too short to drain.
     */
    @Test void reportThroughput() throws InterruptedException {
        Workload warmup = new Workload("warmup", 20000, 400, 0, 8, 1000, 1);
        run(warmup);
        run(warmup);

        System.out.printf("%n%-24s %7s %8s %10s %8s %9s %9s %8s%n",
                "workload", "ms", "lobbies", "lobbies/s", "retries", "contention",
                "starved", "aborts");

        for (int spreadSigma : new int[] {400}) {
            for (int millis : new int[] {25, 50, 100}) {
                String name = spreadSigma == 0 ? "20k, single rating" : "20k, normal s=400";
                Workload workload = new Workload(name, 20000, spreadSigma,
                        spreadSigma == 0 ? 1 : 0, 8, millis, 15);

                List<Result> runs = new ArrayList<>();
                for (int i = 0; i < workload.repeats(); i++) {
                    runs.add(run(workload));
                }
                int lobbies = mean(runs, Result::lobbies);
                System.out.printf("%-24s %7d %8d %10d %8d %9d %9d %8d%n",
                        name, millis, lobbies, (long) lobbies * 1000 / millis,
                        mean(runs, Result::retries), mean(runs, Result::contention),
                        mean(runs, Result::starvation), mean(runs, Result::aborts));
            }
        }
        System.out.println();
    }

    private static int mean(List<Result> runs, java.util.function.ToIntFunction<Result> field) {
        return (int) Math.round(runs.stream().mapToInt(field).average().orElse(0));
    }

    private Result run(Workload workload) throws InterruptedException {
        SkillIndex index = new SkillIndex();
        FairnessHeap heap = new FairnessHeap();
        MatchMaker matcher = new MatchMaker(index, heap, COOLDOWN);

        Random ratings = new Random(SEED);
        Instant now = Instant.now();
        for (int i = 0; i < workload.players(); i++) {
            Player player = new Player(UUID.randomUUID(), ratingFor(workload, ratings),
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

        List<Player> seated = run.lobbies().stream()
                                 .flatMap(lobby -> lobby.members().stream()).toList();
        int distinct = (int) seated.stream().distinct().count();
        int queued = (int) index.playersInRange(1, 5000).flatMap(Set::stream).count();

        return new Result(run.lobbies().size(), seated.size() - distinct, queued,
                matcher.retryCount(), matcher.contentionCount(),
                matcher.starvationCount(), matcher.abortCount(), elapsed);
    }

    /**
     * A real ladder is dense in the middle and thin at the edges, so a normal
     * distribution is closer than a uniform one. Uniform is kept as the case
     * where a window covers the most buckets for the fewest players.
     */
    private static int ratingFor(Workload workload, Random ratings) {
        int rating = workload.sigma() > 0
                ? (int) Math.round(1500 + ratings.nextGaussian() * workload.sigma())
                : 1 + ratings.nextInt(Math.max(1, workload.uniformWidth()));
        return Math.min(5000, Math.max(1, rating));
    }
}
