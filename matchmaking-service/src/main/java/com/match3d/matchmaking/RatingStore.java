package com.match3d.matchmaking;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Ratings by player, in the players table. Players are created outside matchmaking. */
public class RatingStore {

    /** The middle of the 1 to 5000 scale, for a newly created player. */
    static final int STARTING_RATING = 2500;

    private final PlayerRepository players;

    public RatingStore(PlayerRepository players) {
        this.players = players;
    }

    /** Empty for a player with no row. */
    public Optional<Integer> ratingOf(UUID playerId) {
        return players.findById(playerId).map(PlayerRow::getRating);
    }

    /** One query for a whole party. Ids with no row are absent from the result. */
    public Map<UUID, Integer> ratingOf(Collection<UUID> playerIds) {
        Map<UUID, Integer> ratings = new HashMap<>();
        for (PlayerRow row : players.findAllById(playerIds)) ratings.put(row.getId(), row.getRating());
        return ratings;
    }

    /** False if the player already exists, whose rating is then left alone. */
    public boolean create(UUID playerId) {
        return players.insertIfAbsent(playerId, STARTING_RATING) == 1;
    }
}
