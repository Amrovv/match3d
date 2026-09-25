package com.match3d.matchmaking;

import java.util.UUID;

/** Reply to GET /players/{id}. The player's current rating. */
public record PlayerResponse(UUID id, int rating) {
}
