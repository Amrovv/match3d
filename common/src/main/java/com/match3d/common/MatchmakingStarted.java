package com.match3d.common;

import java.time.Instant;
import java.util.Objects;

/**
 * Matchmaking to intake. Matchmaking started with an empty engine, so intake
 * sends every entry it holds again. Sent on the queue EntryMatched uses, so a
 * lobby published before the restart is seen first.
 */
public record MatchmakingStarted(Instant startedAt) {

    public MatchmakingStarted {
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
