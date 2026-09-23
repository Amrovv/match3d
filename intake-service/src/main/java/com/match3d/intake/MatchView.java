package com.match3d.intake;

import java.util.List;
import java.util.UUID;

/** A formed match as a player sees it, both teams as player ids. */
public record MatchView(UUID matchId, List<UUID> teamA, List<UUID> teamB) {

    public MatchView {
        teamA = List.copyOf(teamA);
        teamB = List.copyOf(teamB);
    }
}
