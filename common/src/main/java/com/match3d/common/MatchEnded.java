package com.match3d.common;

import java.util.Objects;
import java.util.UUID;

/** Matchmaking to intake. A match has its result, so its players are free. */
public record MatchEnded(UUID matchId) {

    public MatchEnded {
        Objects.requireNonNull(matchId, "matchId");
    }
}
