package com.match3d.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.PriorityQueue;
import java.util.Comparator;

/**
 * Players from several buckets in one wait time order, longest waiting first.
 *
 * Each bucket is already ordered, so a k way merge over the bucket heads gives
 * the global order without sorting: O(b) to seed b buckets, then O(log b) per
 * player drawn. Sorting the window instead would cost O(m log m) over every
 * candidate in it, to seat nine.
 *
 * Lazy, so a caller that stops early never pays for the players it did not
 * draw. Seeding takes one reference and one head player from each bucket, and
 * everyone behind a head is untouched until they are drawn.
 */
public final class WaitTimeMerge {

    private WaitTimeMerge() {
    }

    /**
     * One cursor per bucket, the player each cursor currently offers, and a
     * heap saying which of those players has waited longest.
     *
     * heads and sources are parallel, heads.get(i) came from sources.get(i), so
     * neither list may be reordered. A null head means that bucket is spent.
     */
    private static final class MergeIterator implements Iterator<Player> {

        /** A cursor per bucket, each walking over the players in one bucket. */
        private final List<Iterator<Player>> sources = new ArrayList<>();

        /** The player currently at the front of each bucket, null if spent. */
        private final List<Player> heads = new ArrayList<>();

        /**
         * Bucket indices, ordered by the head each one currently holds.
         *
         * Indices rather than players, so heads and sources are never reordered.
         * An index is present if and only if its head is a player.
         */
        private final PriorityQueue<Integer> indexHeap =
                new PriorityQueue<>(Comparator.comparing(heads::get, Player.BY_WAIT_TIME));

        /**
         * Seeds one cursor and one head per bucket.
         *
         * Both lists are appended in the same loop turn, which is what keeps
         * their indices in step. An empty bucket is left out of the heap.
         */
        MergeIterator(Stream<Set<Player>> buckets) {
            for (Set<Player> bucket : buckets.toList()) {
                Iterator<Player> source = bucket.iterator();
                sources.add(source);

                boolean hasNext = source.hasNext();
                heads.add(hasNext ? source.next() : null);
                if (hasNext) indexHeap.add(heads.size() - 1);
            }
        }

        @Override
        public boolean hasNext() {
            return !indexHeap.isEmpty();
        }

        /**
         * The longest waiting head across every live bucket.
         *
         * Only the winning bucket is advanced, so a draw is O(log b). A spent
         * bucket is not pushed back and drops out of the merge for good.
         *
         * Throws NoSuchElementException if every bucket is spent.
         */
        @Override
        public Player next() {
            Integer index = indexHeap.poll();
            if (index == null) throw new NoSuchElementException();

            Player next = heads.get(index);
            Iterator<Player> source = sources.get(index);

            Player nextHead = source.hasNext() ? source.next() : null;
            heads.set(index, nextHead);

            if (nextHead != null) indexHeap.add(index);
            return next;
        }
    }

    /**
     * A stream of buckets ordered by rating goes in, a stream of players
     * ordered by wait time comes out.
     *
     * The caller never sees the buckets, and the ordered sequence it draws from
     * exists nowhere in memory, it is produced one player at a time on demand.
     * A caller taking n players pays for n draws and nothing more.
     *
     * Seeding consumes the bucket stream, so the index must not be mutated
     * until the returned stream has been drawn from.
     */
    public static Stream<Player> byWaitTime(Stream<Set<Player>> buckets) {
        return lazily(new MergeIterator(buckets));
    }

    /**
     * Wraps a merging iterator as a lazy stream.
     *
     * Unknown size because counting the players across the buckets would mean
     * walking them. Sequential, because parallel would destroy the ordering
     * this class exists to provide.
     */
    private static Stream<Player> lazily(Iterator<Player> merged) {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        merged, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }
}
