package com.match3d.matchmaking;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Ratings by player, in memory until persistence. Unknown players start mid scale. */
public class RatingStore {

    /** The middle of the 1 to 5000 scale. */
    static final int STARTING_RATING = 2500;

    private final Map<UUID, Integer> ratings = new ConcurrentHashMap<>();

    public int ratingOf(UUID playerId) {
        return ratings.getOrDefault(playerId, STARTING_RATING);
    }

    public void set(UUID playerId, int rating) {
        ratings.put(playerId, rating);
    }
}
