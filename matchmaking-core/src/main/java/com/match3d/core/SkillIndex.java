package com.match3d.core;

import java.util.LinkedHashSet;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
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
 */
public final class SkillIndex {

    private final NavigableMap<Integer, Set<Player>> byRating = new TreeMap<>();
    private int playerSize = 0;

    /**
     * Adds a player, creating their rating's bucket if it is absent.
     *
     * Returns false if the player was already queued at that rating.
     */
    public boolean insert(Player player) {
        boolean added = byRating.computeIfAbsent(player.rating(), r -> new LinkedHashSet<>()).add(player);
        if (added) playerSize++;
        return added;
    }

    /**
     * Removes a player, deleting their rating's bucket if it is left empty.
     *
     * Returns false if the player was not queued at that rating.
     */
    public boolean remove(Player player) {
        Set<Player> bucket = byRating.get(player.rating());
        if (bucket == null) return false;
        boolean removed = bucket.remove(player);
        if (removed) playerSize--;
        if (bucket.isEmpty()) byRating.remove(player.rating());
        return removed;
    }

    /**
     * Every queued player rated between low and high, both bounds inclusive.
     *
     * Lazy. A window can hold thousands of candidates when a lobby needs nine,
     * so nothing is materialised and the caller can stop early.
     *
     * Ordered by rating, and within a rating by wait time, longest first.
     */
    public Stream<Player> playersInRange(int low, int high) {
        return byRating.subMap(low, true, high, true).values().stream().flatMap(Set::stream);
    }

    /**
     * The number of players currently queued, across all ratings.
     */
    public int playerCount() {
        return playerSize;
    }

    /**
     * The number of occupied ratings, which is the tree's size. Exposed so
     * tests can prove empty buckets are deleted, not merely emptied.
     */
    public int ratingCount() {
        return byRating.size();
    }

}
