package com.match3d.matchmaking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One formed match, by player id, as the query endpoints return it. */
public record MatchRecord(UUID matchId, Instant formedAt, List<UUID> teamA, List<UUID> teamB) {

    public MatchRecord {
        teamA = List.copyOf(teamA);
        teamB = List.copyOf(teamB);
    }
}
