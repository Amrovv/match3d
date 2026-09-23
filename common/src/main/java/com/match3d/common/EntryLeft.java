package com.match3d.common;

import java.util.Objects;
import java.util.UUID;

/** Intake to matchmaking. The entry left the queue before being matched. */
public record EntryLeft(UUID entryId) {

    public EntryLeft {
        Objects.requireNonNull(entryId, "entryId");
    }
}
