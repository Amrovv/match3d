package com.match3d.intake;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last match each player was placed in. Grows for as long as intake runs,
 * since match history belongs in a database that does not exist yet.
 *
 * Written by the listener and read by status requests on other threads, so
 * the map is concurrent.
 */
public final class MatchBoard {

    private final Map<UUID, MatchView> byPlayer = new ConcurrentHashMap<>();

    /** Records the match against every player in it. */
    public void record(MatchView match) {
        match.teamA().forEach(player -> byPlayer.put(player, match));
        match.teamB().forEach(player -> byPlayer.put(player, match));
    }

    /** The last match this player was placed in, or null. */
    public MatchView matchOf(UUID playerId) {
        return byPlayer.get(playerId);
    }
}
