package com.match3d.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Queued players in rating order, answering "everyone rated between low and
 * high". A TreeMap keyed by rating, one bucket per rating rather than one node
 * per player, so the tree is bounded by the 5000 possible ratings however many
 * players queue.
 * Buckets are LinkedHashSet, so wait time order is preserved.
 *
 * With nr distinct ratings and b buckets in a window: insert and remove are
 * O(log nr), playersInRange is O(log nr + b) and lazy. Nothing depends on the
 * number of queued players.
 *
 * A bucket exists if and only if it holds a player.
 *
 * A map from id to player sits beside the tree. It makes a lookup by id
 * constant time rather than a walk of the buckets.
 *
 * Every mutation touches both the map and a bucket, in insert and remove and
 * nowhere else. A write reaching one without the other corrupts the index
 * silently.
 *
 * Not synchronised. A caller running it under contention supplies the mutual
 * exclusion.
 */
public final class SkillIndex {

    private final NavigableMap<Integer, Set<Player>> byRating = new TreeMap<>();
    private final Map<UUID, Player> byId = new HashMap<>();

    /**
     * Adds a player, creating their rating's bucket if it is absent.
     *
     * Returns false if that id is already queued.
     */
    public boolean insert(Player player) {
        if (byId.containsKey(player.id())) return false;
        byRating.computeIfAbsent(player.rating(), r -> new LinkedHashSet<>()).add(player);
        byId.put(player.id(), player);
        return true;
    }

    /**
     * Removes a player by id, deleting their bucket if it is left empty.
     *
     * Returns false if that id is not queued.
     */
    public boolean remove(UUID id) {
        Player queued = byId.remove(id);
        if (queued == null) return false;

        byRating.computeIfPresent(queued.rating(), (rating, bucket) -> {
            bucket.remove(queued);
            return bucket.isEmpty() ? null : bucket;
        });
        return true;
    }

    /**
     * Removes a player, deleting their rating's bucket if it is left empty.
     *
     * The rating on the argument is ignored, the id is the address.
     *
     * Returns false if that id is not queued.
     */
    public boolean remove(Player player) {
        return remove(player.id());
    }

    /**
     * Whether that id is currently queued, at any rating.
     */
    public boolean contains(UUID id) {
        return byId.containsKey(id);
    }

    /**
     * Every queued player rated between low and high, both bounds inclusive,
     * still in buckets so the matcher can merge across them for wait time order.
     *
     * Ordered by rating, and within a bucket by wait time, longest first.
     *
     * A stream rather than a list, so nothing is built that the caller does not
     * ask for. A mid distribution window can hold thousands of players when a
     * lobby seats ten, and a list would materialise all of them to hand back
     * ten. The caller pulls only as far as it needs and the rest are never
     * touched.
     *
     * Lazy, and the buckets are live unmodifiable views rather than copies, so
     * the caller must finish drawing before mutating the index.
     */
    public Stream<Set<Player>> playersInRange(int low, int high) {
        return byRating.subMap(low, true, high, true).values().stream()
                       .map(Collections::unmodifiableSet);
    }

    
    /**
     * The number of players currently queued, across all ratings.
     */
    public int playerCount() {
        return byId.size();
    }

    /**
     * The number of occupied ratings, which is the tree's size. Exposed so
     * tests can prove empty buckets are deleted, not merely emptied.
     */
    public int ratingCount() {
        return byRating.size();
    }

}
