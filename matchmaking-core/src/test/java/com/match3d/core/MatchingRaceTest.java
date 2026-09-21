package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchingRaceTest {

    /** One rating, so every worker's window covers every other worker's candidates. */
    private static final int RATING = 1500;

    /** People per round, half alone and half in parties. A multiple of 28, one full cycle. */
    private static final int PLAYERS = 280;

    /** Party sizes in queue order, repeated. Each party is followed by as many solos. */
    private static final int[] PARTY_SIZES = {2, 3, 4, 5};
    private static final int WORKERS = 8;
    private static final int RUN_MILLIS = 25;

    /** Rounds per test. A race caught once in twenty runs is not a test. */
    private static final int ROUNDS = 10;

    /** Everything one run of the pool leaves behind, for a test to read at rest. */
    private record Round(List<QueueEntry> joined, Map<UUID, QueueEntry> entryOf,
                         MatchingWorkerPool.Run run,
                         SkillIndex index, FairnessHeap heap, MatchMaker matcher) {

        /** Every player seated in a lobby, duplicates kept, since duplicates are the point. */
        List<Player> matched() {
            return run.lobbies().stream().flatMap(lobby -> lobby.members().stream()).toList();
        }

        /** Every player still in the index, however many buckets they are spread across. */
        List<QueueEntry> queued() {
            return index.entriesInRange(1, 5000).flatMap(Set::stream).toList();
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

        List<QueueEntry> joined = population(Instant.now().minusSeconds(PLAYERS));
        Map<UUID, QueueEntry> entryOf = new HashMap<>();
        for (QueueEntry entry : joined) {
            index.insert(entry);
            heap.insert(entry);
            entry.members().forEach(member -> entryOf.put(member.id(), entry));
        }

        MatchingWorkerPool pool = new MatchingWorkerPool(matcher, WORKERS);
        pool.start();
        Thread.sleep(RUN_MILLIS);

        return new Round(joined, entryOf, pool.stop(), index, heap, matcher);
    }

    /** Cycles through PARTY_SIZES, each party then as many solos, oldest first. */
    private static List<QueueEntry> population(Instant start) {
        List<QueueEntry> entries = new ArrayList<>();
        Instant at = start;
        for (int people = 0, i = 0; people < PLAYERS; i++) {
            int size = PARTY_SIZES[i % PARTY_SIZES.length];

            List<Player> members = new ArrayList<>();
            for (int m = 0; m < size; m++) members.add(new Player(UUID.randomUUID(), RATING, at));
            entries.add(Party.of(members, at));
            at = at.plusSeconds(1);

            for (int s = 0; s < size; s++) {
                entries.add(new Player(UUID.randomUUID(), RATING, at));
                at = at.plusSeconds(1);
            }
            people += 2 * size;
        }
        return entries;
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

    @Test void testAPartyIsNeverSplit() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            // Each team of each lobby gets its own number, so one set per
            // entry says how many sides its members were spread across.
            Map<QueueEntry, Set<Integer>> sidesOf = new HashMap<>();
            Map<QueueEntry, Integer> seatedOf = new HashMap<>();
            int side = 0;
            for (Lobby lobby : round.run().lobbies()) {
                for (List<Player> team : List.of(lobby.teamA(), lobby.teamB())) {
                    for (Player player : team) {
                        QueueEntry entry = round.entryOf().get(player.id());
                        sidesOf.computeIfAbsent(entry, e -> new HashSet<>()).add(side);
                        seatedOf.merge(entry, 1, Integer::sum);
                    }
                    side++;
                }
            }

            seatedOf.forEach((entry, seated) -> {
                assertEquals(entry.size(), seated, "A party is seated whole or not at all");
                assertEquals(1, sidesOf.get(entry).size(), "A party sits on one team of one lobby");
            });
        }
    }

    // nobody lost

    @Test void testEveryPlayerIsAccountedFor() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            int playersQueued = round.queued().stream().mapToInt(QueueEntry::size).sum();
            assertEquals(PLAYERS, round.matched().size() + playersQueued,
                    "Every player joined is either seated in a lobby or still queued");
        }
    }

    @Test void testAMatchedPlayerNeverReturnsToTheHeap() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            for (Player player : round.matched()) {
                QueueEntry entry = round.entryOf().get(player.id());
                assertFalse(round.heap().contains(entry.id()),
                        "A player already seated in a lobby must not be waiting to anchor another");
            }
        }
    }

    // the structures agree with themselves and each other

    @Test void testTheStructuresStillAgree() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            assertEquals(round.index().entryCount(),
                    round.heap().size() + round.matcher().coolingCount(),
                    "A queued player is in the heap unless they are cooling");
        }
    }

    @Test void testTheIndexCountMatchesItsContents() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            Round round = round();

            assertEquals(round.queued().size(), round.index().entryCount(),
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

            round.index().entriesInRange(1, 5000).forEach(bucket ->
                    assertFalse(bucket.isEmpty(), "A bucket exists only while it holds a player"));
        }
    }

    // proof the run was actually contended

    @Test void testTheWorkersActuallyRacedEachOther() throws InterruptedException {
        // Without this, a green suite cannot be told apart from a suite that
        // stopped contending: a narrower spread, fewer workers or a shorter run
        // would all pass silently while proving nothing. A retry only happens
        // when a verify found a member another worker had already taken.
        int retries = 0;
        for (int r = 0; r < ROUNDS; r++) {
            retries += round().matcher().retryCount();
        }

        assertTrue(retries > 0,
                "Eight workers on one bucket must collide, so a run with no retry at all"
                        + " means the harness has stopped exercising the race");
    }

    @Test void testAnAnchorIsNeverTakenByAnotherWorker() throws InterruptedException {
        // The anchor is claimed out of the index at poll, so no other worker
        // can see them to recruit them. Before that claim this fired on roughly
        // three passes in five.
        for (int r = 0; r < ROUNDS; r++) {
            assertEquals(0, round().matcher().abortCount(),
                    "A claimed anchor cannot be recruited, so no pass should abandon one");
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
