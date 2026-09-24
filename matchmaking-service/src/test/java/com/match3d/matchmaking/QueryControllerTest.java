package com.match3d.matchmaking;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.match3d.core.Lobby;
import com.match3d.core.Player;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** The controller called directly, over matches recorded in Postgres. */
class QueryControllerTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @Autowired private MatchHistory history;
    @Autowired private RatingStore ratings;
    @Autowired private MatchRepository matches;

    private QueryController controller() {
        return new QueryController(history);
    }

    /** A player with a players row, as matchmaking requires. */
    private Player player() {
        UUID id = UUID.randomUUID();
        ratings.create(id);
        return new Player(id, RatingStore.STARTING_RATING, NOW);
    }

    /** Two a side is enough to check the split; the engine owns lobby size. */
    private UUID record(Lobby lobby, Instant formedAt) {
        UUID matchId = UUID.randomUUID();
        history.record(matchId, formedAt, lobby);
        return matchId;
    }

    @Test void testMatchFound() {
        Player a1 = player(), a2 = player(), b1 = player(), b2 = player();
        UUID matchId = record(new Lobby(List.of(a1, a2), List.of(b1, b2)), NOW);

        var response = controller().match(matchId);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        MatchRecord match = response.getBody();
        assertEquals(NOW, match.formedAt());
        assertEquals(Set.of(a1.id(), a2.id()), Set.copyOf(match.teamA()), "Seats are split by side");
        assertEquals(Set.of(b1.id(), b2.id()), Set.copyOf(match.teamB()));
    }

    @Test void testMatchNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, controller().match(UUID.randomUUID()).getStatusCode());
    }

    @Test void testHistoryNewestFirst() {
        Player p = player();
        UUID first = record(new Lobby(List.of(p), List.of(player())), NOW);
        UUID second = record(new Lobby(List.of(player()), List.of(p)), NOW.plusSeconds(60));

        List<UUID> ids = controller().history(p.id()).stream().map(MatchRecord::matchId).toList();
        assertEquals(List.of(second, first), ids, "Either side, newest first");
    }

    @Test void testHistoryEmptyForStranger() {
        assertEquals(List.of(), controller().history(UUID.randomUUID()));
    }

    /** Outside the test transaction, so record commits or rolls back on its own. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testRecordAllOrNothing() {
        Player stranger = new Player(UUID.randomUUID(), 2500, NOW);
        Lobby lobby = new Lobby(List.of(player()), List.of(stranger));
        UUID matchId = UUID.randomUUID();

        try {
            assertThrows(DataIntegrityViolationException.class, () -> history.record(matchId, NOW, lobby),
                    "A seat without a players row breaks the foreign key");
            assertFalse(matches.existsById(matchId), "So the match row is rolled back with it");
        } finally {
            wipe();
        }
    }

    @Test void testHistoryOnlyOwnMatches() {
        Player x = player(), y = player();
        UUID xs = record(new Lobby(List.of(x), List.of(player())), NOW);
        record(new Lobby(List.of(y), List.of(player())), NOW.plusSeconds(60));

        List<UUID> ids = controller().history(x.id()).stream().map(MatchRecord::matchId).toList();
        assertEquals(List.of(xs), ids, "Another player's match is not in X's history");
    }

    @Test void testTeamsHoldOnlyTheirOwnMatch() {
        Player a1 = player(), b1 = player(), a2 = player(), b2 = player();
        UUID first = record(new Lobby(List.of(a1), List.of(b1)), NOW);
        UUID second = record(new Lobby(List.of(a2), List.of(b2)), NOW);

        MatchRecord one = controller().match(first).getBody();
        MatchRecord two = controller().match(second).getBody();
        assertEquals(List.of(a1.id()), one.teamA());
        assertEquals(List.of(b1.id()), one.teamB());
        assertEquals(List.of(a2.id()), two.teamA());
        assertEquals(List.of(b2.id()), two.teamB(), "Seats never cross between matches");
    }
}
