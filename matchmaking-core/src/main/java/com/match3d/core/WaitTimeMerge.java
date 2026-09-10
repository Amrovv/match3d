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
 * Players from several already ordered buckets in one wait time order, longest
 * waiting first. A k way merge over the bucket heads: O(b) to seed b buckets,
 * then O(log b) per player drawn.
 *
 * Lazy. Seeding takes one cursor and one head per bucket, and a caller that
 * stops early never pays for the rest.
 */
public final class WaitTimeMerge {

    private WaitTimeMerge() {
    }

    /**
     * heads and sources are parallel, so neither list may be reordered. A null
     * head means that bucket is spent.
     */
    private static final class MergeIterator implements Iterator<Player> {

        /** A cursor per bucket. */
        private final List<Iterator<Player>> sources = new ArrayList<>();

        /** The player currently at the front of each bucket, null if spent. */
        private final List<Player> heads = new ArrayList<>();

        /**
         * Bucket indices, ordered by the head each holds. Indices rather than
         * players so the parallel lists stay put. Present iff its head is a
         * player.
         */
        private final PriorityQueue<Integer> indexHeap =
                new PriorityQueue<>(Comparator.comparing(heads::get, Player.BY_WAIT_TIME));

        /** Both lists are appended in the same turn, keeping indices in step. */
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
         * The longest waiting head. Only the winning bucket advances, and a
         * spent one is never pushed back. Throws if every bucket is spent.
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
     * Buckets by rating in, players by wait time out. The ordered sequence is
     * produced on demand and exists nowhere in memory.
     *
     * Seeding consumes the bucket stream, so the index must not be mutated
     * until the returned stream has been drawn from.
     */
    public static Stream<Player> byWaitTime(Stream<Set<Player>> buckets) {
        return lazily(new MergeIterator(buckets));
    }

    /**
     * Unknown size, since counting would mean walking. Sequential, since
     * parallel would destroy the ordering this class exists to provide.
     */
    private static Stream<Player> lazily(Iterator<Player> merged) {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        merged, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }
}
