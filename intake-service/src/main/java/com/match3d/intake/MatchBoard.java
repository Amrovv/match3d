package com.match3d.intake;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The match each player is in, until it ends.
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

    /**
     * Forgets the match for every player still showing it. A player already
     * in a newer match keeps that one. Scans every player.
     */
    public void end(UUID matchId) {
        byPlayer.values().removeIf(match -> match.matchId().equals(matchId));
    }

    /** The last match this player was placed in, or null. */
    public MatchView matchOf(UUID playerId) {
        return byPlayer.get(playerId);
    }
}
