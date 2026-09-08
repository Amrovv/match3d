package com.match3d.core;

import java.util.List;
import java.util.Objects;

/**
 * Ten players matched into one game.
 *
 * Immutable. Members are held in the order the matcher chose them, so the
 * anchor, the longest waiting player the lobby was built around, is first.
 *
 * Size is not checked here. The matcher is the only thing that builds a lobby
 * and it only builds full ones, so a check would test the caller, not the type.
 */
public record Lobby(List<Player> members) {

    public Lobby {
        Objects.requireNonNull(members, "members");
        members = List.copyOf(members);
    }

    /**
     * The player whose wait time set the window this lobby was built inside.
     */
    public Player anchor() {
        return members.get(0);
    }
}
