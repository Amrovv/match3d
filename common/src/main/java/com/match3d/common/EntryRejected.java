package com.match3d.common;

import java.util.Objects;
import java.util.UUID;

/** Matchmaking to intake. The entry was never queued. */
public record EntryRejected(UUID entryId, Reason reason) {

    public enum Reason {
        /** The entry id is already queued. Intake ignores this for an entry it holds. */
        DUPLICATE,
        /** A party's members are further apart than the spread cap allows. */
        SPREAD_TOO_WIDE,
        /** A member has no players row in matchmaking. Accounts are created outside matchmaking. */
        UNKNOWN_PLAYER
    }

    public EntryRejected {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(reason, "reason");
    }
}
