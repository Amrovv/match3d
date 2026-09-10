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
 * high". One bucket per rating, so the tree is bounded by the 5000 possible
 * ratings however many players queue. Buckets hold wait time order.
 *
 * With nr occupied ratings and b buckets in a window: insert and remove are
 * O(log nr), playersInRange is O(log nr + b) and lazy. No term is the number
 * of queued players.
 *
 * A bucket exists if and only if it holds a player.
 *
 * The id map beside the tree is the authority on what is queued. Every
 * mutation touches both it and a bucket, in insert and remove and nowhere
 * else. A write reaching one without the other corrupts the index silently.
 *
 * Not synchronised. The caller supplies mutual exclusion.
 */
public final class SkillIndex {

    private final NavigableMap<Integer, Set<Player>> byRating = new TreeMap<>();
    private final Map<UUID, Player> byId = new HashMap<>();

    /** Adds a player. False if that id is already queued, at any rating. */
    public boolean insert(Player player) {
        if (byId.containsKey(player.id())) return false;
        byRating.computeIfAbsent(player.rating(), r -> new LinkedHashSet<>()).add(player);
        byId.put(player.id(), player);
        return true;
    }

    /** Removes by id, deleting the bucket if it empties. False if not queued. */
    public boolean remove(UUID id) {
        Player queued = byId.remove(id);
        if (queued == null) return false;

        byRating.computeIfPresent(queued.rating(), (rating, bucket) -> {
            bucket.remove(queued);
            return bucket.isEmpty() ? null : bucket;
        });
        return true;
    }

    /** The rating on the argument is ignored, the id is the address. */
    public boolean remove(Player player) {
        return remove(player.id());
    }

    /** Whether that id is queued, at any rating. */
    public boolean contains(UUID id) {
        return byId.containsKey(id);
    }

    /**
     * Buckets between low and high inclusive, by rating, each internally by
     * wait time. Left in buckets so the caller can merge across them.
     *
     * Lazy, over live views rather than copies, so the caller must finish
     * drawing before mutating the index.
     */
    public Stream<Set<Player>> playersInRange(int low, int high) {
        return byRating.subMap(low, true, high, true).values().stream()
                       .map(Collections::unmodifiableSet);
    }

    /** Players queued, across all ratings. */
    public int playerCount() {
        return byId.size();
    }

    /** Occupied ratings. Exposed so tests can prove empty buckets are deleted. */
    public int ratingCount() {
        return byRating.size();
    }

}
