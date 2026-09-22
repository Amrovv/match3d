package com.match3d.intake;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.match3d.common.EntryRejected;

/**
 * What a player is doing right now. Only the field belonging to the state is
 * set, and the rest are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusResponse(State state, UUID entryId, MatchView match, EntryRejected.Reason reason) {

    public enum State {
        QUEUED, MATCHED, REFUSED, NOT_QUEUED
    }

    public static StatusResponse queued(UUID entryId) {
        return new StatusResponse(State.QUEUED, entryId, null, null);
    }

    public static StatusResponse matched(MatchView match) {
        return new StatusResponse(State.MATCHED, null, match, null);
    }

    public static StatusResponse refused(EntryRejected.Reason reason) {
        return new StatusResponse(State.REFUSED, null, null, reason);
    }

    public static StatusResponse notQueued() {
        return new StatusResponse(State.NOT_QUEUED, null, null, null);
    }
}
