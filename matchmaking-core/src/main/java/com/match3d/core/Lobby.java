package com.match3d.core;

import java.util.List;
import java.util.Objects;

/**
 * Ten players matched into one game. Immutable, in the order the matcher chose
 * them, so the anchor is first.
 *
 * Size is unchecked: the matcher only builds full ones, so a check here would
 * test the caller.
 */
public record Lobby(List<Player> members) {

    public Lobby {
        Objects.requireNonNull(members, "members");
        members = List.copyOf(members);
    }

    /** The player whose wait set the window this lobby was built inside. */
    public Player anchor() {
        return members.get(0);
    }
}
