package com.match3d.intake;

import java.util.UUID;

/** Reply to an accepted join. The id to leave with and to find the entry by. */
public record JoinResponse(UUID entryId) {
}
