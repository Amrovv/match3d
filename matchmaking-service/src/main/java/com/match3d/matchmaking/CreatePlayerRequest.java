package com.match3d.matchmaking;

import java.util.UUID;

/** Body of POST /players. The id is the account system's own. */
public record CreatePlayerRequest(UUID id) {
}
