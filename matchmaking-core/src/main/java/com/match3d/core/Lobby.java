package com.match3d.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ten players matched into one game, five a side. Immutable, in the order the
 * matcher chose them, so the anchor is first on team A.
 *
 * A party is always whole on one side, which is why the matcher fills two
 * sides of five rather than counting to ten.
 *
 * Sizes are unchecked: the matcher only builds full ones, so a check here
 * would test the caller.
 */
public record Lobby(List<Player> teamA, List<Player> teamB) {

    public Lobby {
        Objects.requireNonNull(teamA, "teamA");
        Objects.requireNonNull(teamB, "teamB");
        teamA = List.copyOf(teamA);
        teamB = List.copyOf(teamB);
    }

    /** Both sides, team A first. */
    public List<Player> members() {
        List<Player> both = new ArrayList<>(teamA.size() + teamB.size());
        both.addAll(teamA);
        both.addAll(teamB);
        return List.copyOf(both);
    }

    /** The player whose wait set the window this lobby was built inside. */
    public Player anchor() {
        return teamA.get(0);
    }
}
