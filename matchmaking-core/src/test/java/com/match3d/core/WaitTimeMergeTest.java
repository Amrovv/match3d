package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaitTimeMergeTest {

    private static final Instant BASE = Instant.parse("2026-09-07T00:00:00Z");

    /** A player queued waitedFor seconds after the base instant. */
    private static Player queuedAt(int waitedFor) {
        return new Player(UUID.randomUUID(), 1000, BASE.plusSeconds(waitedFor));
    }

    /** A bucket in the order given, which is the order the index would hold it in. */
    private static Set<Player> bucket(Player... players) {
        return new LinkedHashSet<>(List.of(players));
    }

    /** The merged order, drawn to exhaustion. */
    @SafeVarargs
    private static List<Player> merge(Set<Player>... buckets) {
        return WaitTimeMerge.byWaitTime(Stream.of(buckets)).toList();
    }

    /**
     * A bucket that counts how many players are actually pulled out of it, so a
     * test can prove the merge does not walk buckets it was not asked to.
     */
    private static Set<Player> countingBucket(AtomicInteger pulled, List<Player> players) {
        return new LinkedHashSet<>(players) {
            @Override
            public Iterator<Player> iterator() {
                Iterator<Player> inner = super.iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() {
                        return inner.hasNext();
                    }

                    @Override public Player next() {
                        pulled.incrementAndGet();
                        return inner.next();
                    }
                };
            }
        };
    }

    // empty cases

    @Test void testNoBucketsYieldsNothing() {
        assertEquals(List.of(), WaitTimeMerge.byWaitTime(Stream.of()).toList(),
                "An empty window has nobody to merge");
    }

    @Test void testEmptyBucketIsSkipped() {
        Player only = queuedAt(0);

        assertEquals(List.of(only), merge(bucket(), bucket(only), bucket()),
                "A bucket with no players contributes nothing and must not stall the merge");
    }

    // ordering

    @Test void testSingleBucketKeepsItsOwnOrder() {
        Player first = queuedAt(0);
        Player second = queuedAt(60);
        Player third = queuedAt(120);

        assertEquals(List.of(first, second, third), merge(bucket(first, second, third)),
                "One bucket is already ordered, so the merge passes it through untouched");
    }

    @Test void testMergesAcrossBucketsByWaitTime() {
        // Bucket order is rating order, so the oldest player sits in the last
        // bucket here. Concatenating the buckets would get this wrong.
        Player oldest = queuedAt(0);
        Player middle = queuedAt(60);
        Player newest = queuedAt(120);

        assertEquals(List.of(oldest, middle, newest), merge(bucket(newest), bucket(middle), bucket(oldest)),
                "Players come out in wait time order however the buckets were arranged by rating");
    }

    @Test void testInterleavesTwoBuckets() {
        Player a = queuedAt(0);
        Player b = queuedAt(10);
        Player c = queuedAt(20);
        Player d = queuedAt(30);

        assertEquals(List.of(a, b, c, d), merge(bucket(a, c), bucket(b, d)),
                "The merge takes from whichever bucket currently offers the longest waiter");
    }

    @Test void testDrawsEveryPlayerExactlyOnce() {
        List<Player> everyone = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            everyone.add(queuedAt(i));
        }

        List<Player> merged = merge(
                bucket(everyone.get(0), everyone.get(3), everyone.get(9), everyone.get(20)),
                bucket(everyone.get(1), everyone.get(4), everyone.get(15)),
                bucket(everyone.get(2), everyone.get(7), everyone.get(11), everyone.get(29)));

        assertEquals(11, merged.size(), "Every player in every bucket is handed out, and none twice");
        assertEquals(List.of(everyone.get(0), everyone.get(1), everyone.get(2), everyone.get(3),
                        everyone.get(4), everyone.get(7), everyone.get(9), everyone.get(11),
                        everyone.get(15), everyone.get(20), everyone.get(29)), merged,
                "The whole draw is in wait time order, not merely its first few");
    }

    @Test void testTiesBreakOnId() {
        Instant sameMoment = BASE.plusSeconds(42);
        Player one = new Player(UUID.randomUUID(), 1000, sameMoment);
        Player other = new Player(UUID.randomUUID(), 2000, sameMoment);

        Player expectedFirst = one.id().compareTo(other.id()) < 0 ? one : other;

        assertEquals(expectedFirst, merge(bucket(one), bucket(other)).get(0),
                "Identical queue times order by id, deterministically");
    }

    @Test void testAgreesWithTheFairnessHeap() {
        // The heap picks the anchor and the merge fills the seats, so the two
        // must never disagree about who has waited longest. Both read
        // Player.BY_WAIT_TIME, and this is what proves it.
        FairnessHeap heap = new FairnessHeap();
        List<Player> odd = new ArrayList<>();
        List<Player> even = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Player p = queuedAt(i * 3);
            heap.insert(p);
            (i % 2 == 0 ? even : odd).add(p);
        }

        List<Player> drained = new ArrayList<>();
        Player next;
        while ((next = heap.poll()) != null) {
            drained.add(next);
        }

        assertEquals(drained, merge(new LinkedHashSet<>(even), new LinkedHashSet<>(odd)),
                "The merge and the heap must hand players out in exactly the same order");
    }

    // laziness

    @Test void testDrawsOnlyWhatTheCallerAsksFor() {
        List<Player> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add(queuedAt(i));
        }
        AtomicInteger pulled = new AtomicInteger();

        List<Player> taken = WaitTimeMerge.byWaitTime(Stream.of(countingBucket(pulled, many)))
                                          .limit(2)
                                          .toList();

        assertEquals(2, taken.size(), "Only two players were asked for");
        assertEquals(3, pulled.get(),
                "One pull seeds the bucket's head, then each of the two draws refills it,"
                        + " so the other 997 are never touched");
    }

    @Test void testSeedingTouchesOneHeadPerBucket() {
        AtomicInteger pulled = new AtomicInteger();
        List<Player> fat = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            fat.add(queuedAt(i));
        }

        WaitTimeMerge.byWaitTime(Stream.of(countingBucket(pulled, fat)));

        assertEquals(1, pulled.get(),
                "Seeding is the one eager step, and it takes a single head from the bucket."
                        + " The other 499 wait until they are drawn");
    }

    // iterator contract

    @Test void testExhaustedIteratorThrowsNeg() {
        Iterator<Player> merged = WaitTimeMerge.byWaitTime(Stream.of(bucket(queuedAt(0)))).iterator();
        merged.next();

        assertFalse(merged.hasNext(), "The only player has been drawn, so nothing remains");
        assertThrows(NoSuchElementException.class, merged::next,
                "Drawing past the end is a caller bug, not an empty result");
    }
}
