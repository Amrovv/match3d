package com.match3d.common;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Intake to matchmaking. One entry joined the queue, a solo when memberIds
 * holds one id, a party otherwise.
 *
 * A solo's entryId is the player's own id. A party's is fresh per join.
 */
public record EntryQueued(UUID entryId, List<UUID> memberIds, Instant queuedAt) {

    public EntryQueued {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(queuedAt, "queuedAt");
        memberIds = List.copyOf(memberIds);
    }
}
