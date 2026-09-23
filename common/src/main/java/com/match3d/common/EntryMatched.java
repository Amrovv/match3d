package com.match3d.common;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Matchmaking to intake. One lobby formed, as entry ids by team.
 *
 * One event per lobby rather than per entry, so intake learns of every entry
 * in it or none of them.
 */
public record EntryMatched(UUID matchId, List<UUID> teamA, List<UUID> teamB) {

    public EntryMatched {
        Objects.requireNonNull(matchId, "matchId");
        teamA = List.copyOf(teamA);
        teamB = List.copyOf(teamB);
    }
}
