package com.match3d.common;

import java.util.Objects;
import java.util.UUID;

/**
 * Matchmaking to intake. The engine holds the entry, at this rating: a solo's
 * own, or a party's as the engine matches it. Intake estimates its wait from
 * the heartbeat's bands at this rating.
 */
public record EntryAccepted(UUID entryId, int rating) {

    public EntryAccepted {
        Objects.requireNonNull(entryId, "entryId");
    }
}
