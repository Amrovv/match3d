package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FairnessHeapTest {

    private static final Instant BASE = Instant.parse("2026-09-07T00:00:00Z");

    private FairnessHeap heap;

    @BeforeEach
    void setUp() {
        heap = new FairnessHeap();
    }

    /** A player queued waitedFor seconds after the base instant. */
    private Player queuedAt(int waitedFor) {
        return new Player(UUID.randomUUID(), 1000, BASE.plusSeconds(waitedFor));
    }

    /** Drains the heap, returning players in the order it hands them out. */
    private List<Player> drain() {
        List<Player> drained = new ArrayList<>();
        Player next;
        while ((next = heap.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }

    /** Fails unless the drained order is by queuedAt ascending, then by id. */
    private void assertDrainsInWaitTimeOrder() {
        List<Player> drained = drain();
        List<Player> expected = new ArrayList<>(drained);
        expected.sort(java.util.Comparator.comparing(Player::queuedAt).thenComparing(Player::id));

        assertEquals(expected, drained, "The heap must hand out the longest waiting player first");
    }

    // insert

    @Test void testInsertPos() {
        assertTrue(heap.insert(queuedAt(0)), "A player not yet queued should be inserted");
        assertEquals(1, heap.size(), "One player is queued");
    }

    @Test void testInsertDuplicateIdNeg() {
        Player p = queuedAt(0);
        heap.insert(p);

        assertFalse(heap.insert(p), "The same id cannot be queued twice");
        assertEquals(1, heap.size(), "A rejected insert must not change the size");
    }

    @Test void testInsertGrowsBeyondInitialCapacity() {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            players.add(queuedAt(i));
        }
        Collections.shuffle(players, new Random(1));
        players.forEach(heap::insert);

        assertEquals(100, heap.size(), "Every player was inserted, so the array grew");
        assertDrainsInWaitTimeOrder();
    }

    // peek and poll

    @Test void testPeekEmpty() {
        assertNull(heap.peek(), "An empty heap has nobody waiting");
    }

    @Test void testPollEmpty() {
        assertNull(heap.poll(), "An empty heap has nobody to hand out");
    }

    @Test void testPeekDoesNotRemove() {
        Player p = queuedAt(0);
        heap.insert(p);

        assertEquals(p, heap.peek(), "peek returns the longest waiting player");
        assertEquals(p, heap.peek(), "peek is repeatable");
        assertEquals(1, heap.size(), "peek must not remove anyone");
    }

    @Test void testPollReturnsLongestWaiting() {
        Player latest = queuedAt(300);
        Player earliest = queuedAt(0);
        Player middle = queuedAt(120);
        heap.insert(latest);
        heap.insert(earliest);
        heap.insert(middle);

        assertEquals(earliest, heap.poll(), "The player who has waited longest comes out first");
        assertEquals(2, heap.size(), "poll removes the player it returns");
    }

    @Test void testPollDrainsInWaitTimeOrder() {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            players.add(queuedAt(i * 7));
        }
        Collections.shuffle(players, new Random(2));
        players.forEach(heap::insert);

        assertDrainsInWaitTimeOrder();
    }

    // remove

    @Test void testRemoveNeg() {
        heap.insert(queuedAt(0));

        assertFalse(heap.remove(UUID.randomUUID()), "An id that was never queued cannot be removed");
        assertEquals(1, heap.size(), "A failed remove must not change the size");
    }

    @Test void testRemoveRoot() {
        Player earliest = queuedAt(0);
        Player second = queuedAt(60);
        heap.insert(earliest);
        heap.insert(second);
        heap.insert(queuedAt(120));

        assertTrue(heap.remove(earliest.id()), "The root can be removed by id");
        assertEquals(second, heap.peek(), "The next longest waiting player becomes the root");
        assertDrainsInWaitTimeOrder();
    }

    @Test void testRemoveLastElement() {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Player p = queuedAt(i);
            players.add(p);
            heap.insert(p);
        }

        // Inserted in ascending wait time, so the newest player sits at the bottom.
        Player last = players.get(9);
        assertTrue(heap.remove(last.id()), "The last element in the array can be removed");
        assertEquals(9, heap.size(), "The heap shrank by one");
        assertFalse(heap.contains(last.id()), "The removed player is gone");
        assertDrainsInWaitTimeOrder();
    }

    @Test void testRemoveFromMiddle() {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            players.add(queuedAt(i * 5));
        }
        Collections.shuffle(players, new Random(3));
        players.forEach(heap::insert);

        Player victim = players.get(7);
        assertTrue(heap.remove(victim.id()), "A player in the middle of the heap can be removed");
        assertEquals(19, heap.size(), "The heap shrank by one");
        assertFalse(heap.contains(victim.id()), "The removed player is gone");
        assertDrainsInWaitTimeOrder();
    }

    @Test void testRemoveEachPositionInTurn() {
        // Sweeps every removal position across several heap shapes. A single
        // shape is not enough: whether the moved player has to sift up, down,
        // or not at all depends on the layout, and one seed only hits some of
        // those cases.
        for (int seed = 1; seed <= 10; seed++) {
            for (int victim = 0; victim < 20; victim++) {
                heap = new FairnessHeap();
                List<Player> players = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    players.add(queuedAt(i * 5));
                }
                Collections.shuffle(players, new Random(seed));
                players.forEach(heap::insert);

                List<Player> victims = new ArrayList<>();
                for (int offset = 0; offset < 5; offset++) {
                    victims.add(players.get((victim + offset * 4) % 20));
                }
                for (Player removed : victims) {
                    assertTrue(heap.remove(removed.id()), "Every queued player must be removable");
                    assertFalse(heap.contains(removed.id()), "The removed player is gone");
                }

                List<Player> expected = new ArrayList<>(players);
                expected.removeAll(victims);
                expected.sort(java.util.Comparator.comparing(Player::queuedAt).thenComparing(Player::id));

                assertEquals(expected, drain(),
                        "Seed " + seed + ", removals from position " + victim + " left the heap out of order");
            }
        }
    }

    @Test void testRemoveTwiceNeg() {
        Player p = queuedAt(0);
        heap.insert(p);
        heap.insert(queuedAt(60));
        heap.remove(p.id());

        assertFalse(heap.remove(p.id()), "A player already removed cannot be removed again");
        assertEquals(1, heap.size(), "The size must not be decremented twice");
    }

    @Test void testRemoveOnlyElement() {
        Player p = queuedAt(0);
        heap.insert(p);

        assertTrue(heap.remove(p.id()), "The only queued player can be removed");
        assertEquals(0, heap.size(), "The heap is empty");
        assertNull(heap.peek(), "An emptied heap has nobody waiting");
    }

    @Test void testRemoveThenInsertSameId() {
        Player p = queuedAt(0);
        heap.insert(p);
        heap.remove(p.id());

        assertTrue(heap.insert(p), "An id is free to queue again once it has been removed");
        assertEquals(1, heap.size(), "The player is queued once");
    }

    // ties and invariants

    @Test void testTiesBreakOnId() {
        Instant sameMoment = BASE.plusSeconds(42);
        Player one = new Player(UUID.randomUUID(), 1000, sameMoment);
        Player other = new Player(UUID.randomUUID(), 2000, sameMoment);
        heap.insert(one);
        heap.insert(other);

        Player expectedFirst = one.id().compareTo(other.id()) < 0 ? one : other;
        assertEquals(expectedFirst, heap.poll(), "Identical queue times order by id, deterministically");
    }

    @Test void testContains() {
        Player p = queuedAt(0);
        assertFalse(heap.contains(p.id()), "A player not yet queued is not contained");

        heap.insert(p);
        assertTrue(heap.contains(p.id()), "A queued player is contained");

        heap.remove(p.id());
        assertFalse(heap.contains(p.id()), "A removed player is no longer contained");
    }

    @Test void testRandomisedMixedSequence() {
        Random random = new Random(4);
        List<Player> queued = new ArrayList<>();
        Set<UUID> removed = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            if (queued.isEmpty() || random.nextInt(3) != 0) {
                Player p = queuedAt(random.nextInt(10_000));
                if (heap.insert(p)) queued.add(p);
            } else {
                Player victim = queued.remove(random.nextInt(queued.size()));
                assertTrue(heap.remove(victim.id()), "A queued player must be removable");
                removed.add(victim.id());
            }
        }

        assertEquals(queued.size(), heap.size(), "The heap holds exactly the players never removed");
        removed.forEach(id -> assertFalse(heap.contains(id), "A removed player must not come back"));
        assertDrainsInWaitTimeOrder();
    }
}
