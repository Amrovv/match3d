package com.match3d.intake;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.match3d.common.EntryRejected;

/**
 * What a player is doing right now. Only the field belonging to the state is
 * set, and the rest are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusResponse(State state, UUID entryId, Matchmaking matchmaking, Long estimatedWaitSeconds,
                             MatchView match, EntryRejected.Reason reason) {

    public enum State {
        QUEUED, MATCHED, REFUSED, NOT_QUEUED
    }

    /** Whether matchmaking's heartbeat is current, shown to a queued player. */
    public enum Matchmaking {
        UP, DOWN
    }

    /** The estimate is left out while down, before acceptance, or with nothing recent to average. */
    public static StatusResponse queued(UUID entryId, Matchmaking matchmaking, Long estimatedWaitSeconds) {
        return new StatusResponse(State.QUEUED, entryId, matchmaking, estimatedWaitSeconds, null, null);
    }

    public static StatusResponse matched(MatchView match) {
        return new StatusResponse(State.MATCHED, null, null, null, match, null);
    }

    public static StatusResponse refused(EntryRejected.Reason reason) {
        return new StatusResponse(State.REFUSED, null, null, null, null, reason);
    }

    public static StatusResponse notQueued() {
        return new StatusResponse(State.NOT_QUEUED, null, null, null, null, null);
    }
}
