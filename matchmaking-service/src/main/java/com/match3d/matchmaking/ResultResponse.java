package com.match3d.matchmaking;

import java.util.UUID;

/** Reply to a result that ended its match. */
public record ResultResponse(UUID matchId, String winner) {
}
