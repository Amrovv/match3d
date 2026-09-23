package com.match3d.intake;

import java.util.UUID;

/** Body of POST /queue/leave. The entry id the join replied with. */
public record LeaveRequest(UUID entryId) {
}
