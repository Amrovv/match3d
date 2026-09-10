package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchingRaceTest {

    /** One rating, so every worker's window covers every other worker's candidates. */
    private static final int RATING = 1500;

    private static final int PLAYERS = 300;
    private static final int WORKERS = 8;
    private static final int RUN_MILLIS = 25;

    /** Rounds per test. A race caught once in twenty runs is not a test. */
    private static final int ROUNDS = 10;

    /** Everything one run of the pool leaves behind, for a test to read at rest. */
    private record Round(List<Player> joined, MatchingWorkerPool.Run run,
                         SkillIndex index, FairnessHeap heap, MatchMaker matcher) {

        /** Every player seated in a lobby, duplicates kept, since duplicates are the point. */
        List<Player> matched() {
            return run.lobbies().stream().flatMap(lobby -> lobby.members().stream()).toList();
        }

        /** Every player still in the index, however many buckets they are spread across. */
        List<Player> queued() {
            return index.playersInRange(1, 5000).flatMap(Set::stream).toList();
        }
    }

    /**
     * One run against a fresh engine.
     *
     * A single tight cluster, all at one rating and one second apart, so every
     * worker anchors and recruits out of the same bucket. A thin spread across
     * many ratings would have workers looking at disjoint candidates, and the
     * races would rarely fire.
     */
    private Round round() throws InterruptedException {
        SkillIndex index = new SkillIndex();
        FairnessHeap heap = new FairnessHeap();
        MatchMaker matcher = new MatchMaker(index, heap);

        Instant now = Instant.now();
        List<Player> joined = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            Player player = new Player(UUID.randomUUID(), RATING, now.minusSeconds(PLAYERS - i));
            index.insert(player);
            heap.insert(player);
            joined.add(player);
        }

        MatchingWorkerPool pool = new MatchingWorkerPool(matcher, WORKERS);
        pool.start();
        Thread.sleep(RUN_MILLIS);

        return new Round(joined, pool.stop(), index, heap, matcher);
    }

    // one player, one lobby

    @Test void testAPlayerIsNeverInTwoLobbies() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            Set<UUID> seen = new HashSet<>();
            for (Player player : round.matched()) {
                assertTrue(seen.add(player.id()),
                        "A player seated in one lobby cannot also be seated in another");
            }
        }
    }

    // Passes today and is kept deliberately. A pass draws each player from the
    // index at most once, so a lobby cannot seat the same person twice while
    // the index holds one entry per player. It becomes reachable at race 5,
    // where a duplicate join at two ratings leaves two entries for one id, and
    // this is the assertion that would catch it.

    @Test void testNoLobbyHoldsThePlayerTwice() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            for (Lobby lobby : round.run().lobbies()) {
                Set<UUID> ids = new HashSet<>();
                lobby.members().forEach(member -> ids.add(member.id()));
                assertEquals(lobby.members().size(), ids.size(),
                        "A lobby seats ten distinct players");
            }
        }
    }

    // nobody lost

    @Test void testEveryPlayerIsAccountedFor() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            assertEquals(PLAYERS, round.matched().size() + round.queued().size(),
                    "Every player joined is either seated in a lobby or still queued");
        }
    }

    @Test void testAMatchedPlayerNeverReturnsToTheHeap() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            for (Player player : round.matched()) {
                assertFalse(round.heap().contains(player.id()),
                        "A player already seated in a lobby must not be waiting to anchor another");
            }
        }
    }

    // the structures agree with themselves and each other

    @Test void testTheStructuresStillAgree() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            assertEquals(round.index().playerCount(),
                    round.heap().size() + round.matcher().coolingCount(),
                    "A queued player is in the heap unless they are cooling");
        }
    }

    @Test void testTheIndexCountMatchesItsContents() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            assertEquals(round.queued().size(), round.index().playerCount(),
                    "The index's count is the number of players actually in its buckets");
        }
    }

    // Passes today and is kept deliberately. computeIfAbsent in insert and the
    // null return in remove can interleave, and the TreeMap is structurally
    // modified by both with nothing guarding it, so an empty bucket surviving
    // is possible in principle. It has not been observed at this contention
    // level, and the invariant is worth stating under load rather than only in
    // SkillIndexTest.

    @Test void testNoBucketSurvivesEmpty() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            round.index().playersInRange(1, 5000).forEach(bucket ->
                    assertFalse(bucket.isEmpty(), "A bucket exists only while it holds a player"));
        }
    }

    // what the workers survived

    @Test void testNoWorkerFailed() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            assertEquals(List.of(), round.run().failures(),
                    "A worker should not have to survive an exception mid pass");
        }
    }
}
