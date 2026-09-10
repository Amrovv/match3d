package com.match3d.core;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillIndexTest {

    private static final Instant BASE = Instant.parse("2026-09-07T00:00:00Z");

    private SkillIndex index;
    private int tick;

    @BeforeEach
    void setUp() {
        index = new SkillIndex();
        tick = 0;
    }

    /** A fresh player at the given rating, queued later than every earlier one. */
    private Player player(int rating) {
        return player(UUID.randomUUID(), rating);
    }

    /** A player with a chosen id, so tests can rebuild the same identity. */
    private Player player(UUID id, int rating) {
        return new Player(id, rating, BASE.plusSeconds(tick++));
    }

    /** The window flattened back to players, for the cases that only care about members. */
    private List<Player> range(int low, int high) {
        return index.playersInRange(low, high).flatMap(Set::stream).toList();
    }

    // insert

    @Test void testInsertPos() {
        assertTrue(index.insert(player(1000)), "A player not yet queued should be inserted");
        assertEquals(1, index.playerCount(), "One player was inserted");
        assertEquals(1, index.ratingCount(), "One rating is now occupied");
    }

    @Test void testInsertNeg() {
        Player p = player(1000);
        index.insert(p);

        assertFalse(index.insert(p), "The same player cannot be queued twice at one rating");
        assertEquals(1, index.playerCount(), "A rejected insert must not change the count");
        assertEquals(1, index.ratingCount(), "A rejected insert must not add a rating");
    }

    @Test void testInsertSharedRating() {
        index.insert(player(1000));
        index.insert(player(1000));

        assertEquals(2, index.playerCount(), "Both players are queued");
        assertEquals(1, index.ratingCount(), "Both share one bucket, so one rating is occupied");
    }

    @Test void testInsertDistinctRatings() {
        index.insert(player(1000));
        index.insert(player(2000));

        assertEquals(2, index.playerCount(), "Both players are queued");
        assertEquals(2, index.ratingCount(), "Two ratings are occupied");
    }

    // remove

    @Test void testRemovePos() {
        Player p = player(1000);
        index.insert(p);

        assertTrue(index.remove(p), "A queued player should be removed");
        assertEquals(0, index.playerCount(), "The queue is empty again");
    }

    @Test void testRemoveNeg() {
        index.insert(player(1000));

        assertFalse(index.remove(player(1000)), "A player who never queued cannot be removed");
        assertEquals(1, index.playerCount(), "A failed remove must not change the count");
    }

    @Test void testInsertNegSameIdDifferentRating() {
        UUID id = UUID.randomUUID();
        index.insert(player(id, 1000));

        assertFalse(index.insert(player(id, 2000)),
                "An id is queued at most once, whatever rating the second join carries");
        assertEquals(1, index.playerCount(), "A rejected insert must not change the count");
        assertEquals(1, index.ratingCount(), "A rejected insert must not occupy a second rating");
    }

    // contains

    @Test void testContainsPos() {
        Player p = player(1000);
        index.insert(p);

        assertTrue(index.contains(p.id()), "A queued id is contained");
    }

    @Test void testContainsNegAfterRemoval() {
        Player p = player(1000);
        index.insert(p);
        index.remove(p);

        assertFalse(index.contains(p.id()), "A removed id is no longer contained");
    }

    @Test void testRemoveByIdPos() {
        Player p = player(1000);
        index.insert(p);

        assertTrue(index.remove(p.id()), "A queued id should be removable without the player");
        assertEquals(0, index.playerCount(), "The player is no longer queued");
    }

    @Test void testRemoveIgnoresAStaleRating() {
        UUID id = UUID.randomUUID();
        index.insert(player(id, 1000));

        assertTrue(index.remove(player(id, 2000)),
                "The id is the address, so a stale rating still finds the entry");
        assertEquals(0, index.playerCount(), "The player is no longer queued at any rating");
        assertEquals(0, index.ratingCount(), "Their bucket was deleted once it emptied");
    }

    @Test void testRemoveDeletesEmptyBucket() {
        Player p = player(1000);
        index.insert(p);
        index.remove(p);

        assertEquals(0, index.ratingCount(), "An emptied bucket is deleted, not left behind");
    }

    @Test void testRemoveKeepsOccupiedBucket() {
        Player stays = player(1000);
        Player goes = player(1000);
        index.insert(stays);
        index.insert(goes);
        index.remove(goes);

        assertEquals(1, index.ratingCount(), "The bucket still holds a player, so it survives");
        assertEquals(1, index.playerCount(), "One of the two players remains");
    }

    @Test void testRemoveTwiceNeg() {
        Player p = player(1000);
        index.insert(p);
        index.remove(p);

        assertFalse(index.remove(p), "A player already removed cannot be removed again");
        assertEquals(0, index.playerCount(), "The count must not be decremented twice");
    }

    // playersInRange

    @Test void testRangeEmptyIndex() {
        assertEquals(List.of(), range(0, 5000), "An empty index has nobody in any window");
    }

    @Test void testRangeNoMatchesNeg() {
        index.insert(player(1000));
        index.insert(player(2000));

        assertEquals(List.of(), range(1400, 1600), "No player sits inside this window");
    }

    @Test void testRangeAllPos() {
        Player low = player(500);
        Player mid = player(1500);
        Player high = player(2500);
        index.insert(low);
        index.insert(mid);
        index.insert(high);

        assertEquals(List.of(low, mid, high), range(0, 5000), "A window covering everyone returns everyone");
    }

    @Test void testRangeLowBoundInclusive() {
        Player onBound = player(1000);
        index.insert(onBound);

        assertEquals(List.of(onBound), range(1000, 1200), "A player sitting exactly on the low bound is included");
    }

    @Test void testRangeHighBoundInclusive() {
        Player onBound = player(1200);
        index.insert(onBound);

        assertEquals(List.of(onBound), range(1000, 1200), "A player sitting exactly on the high bound is included");
    }

    @Test void testRangeSingleRating() {
        Player wanted = player(1000);
        index.insert(wanted);
        index.insert(player(1001));

        assertEquals(List.of(wanted), range(1000, 1000), "A window of one rating returns only that bucket");
    }

    @Test void testRangeOrderedByRating() {
        Player high = player(1200);
        Player low = player(1000);
        index.insert(high);
        index.insert(low);

        assertEquals(List.of(low, high), range(0, 5000), "Players come back in ascending rating order");
    }

    @Test void testRangeOrderedByWaitTimeWithinRating() {
        Player first = player(1000);
        Player second = player(1000);
        Player third = player(1000);
        index.insert(first);
        index.insert(second);
        index.insert(third);

        assertEquals(List.of(first, second, third), range(1000, 1000),
                "Within one rating, the longest waiting player comes first");
    }

    @Test void testRangeIsLazy() {
        for (int rating = 1000; rating < 1050; rating++) {
            index.insert(player(rating));
        }

        AtomicInteger pulled = new AtomicInteger();
        List<Player> taken = index.playersInRange(1000, 1049)
                                  .flatMap(Set::stream)
                                  .peek(p -> pulled.incrementAndGet())
                                  .limit(2)
                                  .toList();

        assertEquals(2, taken.size(), "Only two players were asked for");
        assertEquals(2, pulled.get(), "Only two players were pulled, the other 48 were never touched");
    }

    @Test void testRangeInvertedWindow() {
        assertThrows(IllegalArgumentException.class, () -> index.playersInRange(1500, 1000),
                "An inverted window is a caller bug, not an empty result");
    }

    @Test void testRangeYieldsOneBucketPerOccupiedRating() {
        index.insert(player(1000));
        index.insert(player(1000));
        index.insert(player(1002));

        List<Set<Player>> buckets = index.playersInRange(1000, 1002).toList();

        assertEquals(2, buckets.size(), "Two ratings are occupied, so two buckets come back");
        assertEquals(2, buckets.get(0).size(), "The first bucket holds both players at 1000");
        assertEquals(1, buckets.get(1).size(), "The second holds the lone player at 1002");
    }

    @Test void testRangeNeverYieldsAnEmptyBucket() {
        Player only = player(1000);
        index.insert(only);
        index.insert(player(1002));
        index.remove(only);

        assertTrue(index.playersInRange(0, 5000).noneMatch(Set::isEmpty),
                "A bucket exists only while it holds a player, so none can come back empty");
    }

    @Test void testRangeBucketsAreUnmodifiableNeg() {
        index.insert(player(1000));
        Set<Player> bucket = index.playersInRange(1000, 1000).findFirst().orElseThrow();

        assertThrows(UnsupportedOperationException.class, () -> bucket.add(player(1000)),
                "A bucket handed out is a view, not a way into the index");
    }

    @Test void testRangeIsLazyWithinOneBucket() {
        for (int i = 0; i < 5000; i++) {
            index.insert(player(1000));
        }

        AtomicInteger pulled = new AtomicInteger();
        List<Player> taken = index.playersInRange(1000, 1000)
                                  .flatMap(Set::stream)
                                  .peek(p -> pulled.incrementAndGet())
                                  .limit(2)
                                  .toList();

        assertEquals(2, taken.size(), "Only two players were asked for");
        assertEquals(2, pulled.get(),
                "One fat bucket must not be walked to its end, the matcher relies on stopping early");
    }

    @Test void testRangeThrowsIfTheIndexIsMutatedMidDrawNeg() {
        for (int rating = 1000; rating < 1010; rating++) {
            index.insert(player(rating));
        }

        assertThrows(ConcurrentModificationException.class,
                () -> index.playersInRange(1000, 1009).flatMap(Set::stream).forEach(index::remove),
                "The window is a live view, so the caller must finish drawing before it mutates");
    }

    // counters

    @Test void testCountsAfterMixedSequence() {
        Player a = player(1000);
        Player b = player(1000);
        Player c = player(2000);
        Player neverQueued = player(3000);

        index.insert(a);
        index.insert(b);
        index.insert(c);
        index.insert(a);
        index.remove(b);
        index.remove(neverQueued);
        index.remove(b);

        assertEquals(2, index.playerCount(), "Only a and c remain");
        assertEquals(2, index.ratingCount(), "Ratings 1000 and 2000 are occupied");
        assertEquals(index.playersInRange(Integer.MIN_VALUE, Integer.MAX_VALUE).flatMap(Set::stream).count(),
                index.playerCount(), "The counter must not drift from what the buckets actually hold");
    }
}
