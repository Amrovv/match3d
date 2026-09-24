package com.match3d.matchmaking;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

/** Ends a match and moves its players' ratings, as one transaction. */
public class MatchResults {

    /** Flat, won or lost. A proper Elo update is future work. */
    static final int RATING_CHANGE = 100;

    public enum Outcome {
        /** This call set the result and moved the ratings. Answered 200. */
        ENDED,
        /** The match had a result already; nothing changed. Answered 409. */
        ALREADY_ENDED,
        /** No match with this id; nothing changed. Answered 404. */
        UNKNOWN_MATCH
    }

    private final MatchRepository matches;
    private final PlayerMatchRepository playerMatches;
    private final PlayerRepository players;
    private final Clock clock;

    public MatchResults(MatchRepository matches, PlayerMatchRepository playerMatches, PlayerRepository players, Clock clock) {
        this.matches = matches;
        this.playerMatches = playerMatches;
        this.players = players;
        this.clock = clock;
    }

    /**
     * Ratings move only if this call set the result, so a repeat changes
     * nothing. Publishing is the caller's, after this has committed.
     */
    @Transactional
    public Outcome end(UUID matchId, char winner) {
        if (matches.endIfOpen(matchId, String.valueOf(winner), clock.instant()) == 0) {
            return matches.existsById(matchId) ? Outcome.ALREADY_ENDED : Outcome.UNKNOWN_MATCH;
        }

        List<UUID> won = new ArrayList<>();
        List<UUID> lost = new ArrayList<>();
        for (PlayerMatchRow played : playerMatches.findByKeyMatchIdIn(List.of(matchId))) {
            (played.getSide() == winner ? won : lost).add(played.getPlayerId());
        }
        players.adjust(won, RATING_CHANGE);
        players.adjust(lost, -RATING_CHANGE);
        return Outcome.ENDED;
    }
}
