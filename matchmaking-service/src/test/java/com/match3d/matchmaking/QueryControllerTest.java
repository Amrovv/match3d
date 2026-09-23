package com.match3d.matchmaking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

/** The controller called directly, over a history filled by hand. */
class QueryControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private final MatchHistory history = new MatchHistory();
    private final QueryController controller = new QueryController(history);

    private MatchRecord record(UUID player, Instant at) {
        MatchRecord match = new MatchRecord(UUID.randomUUID(), at, List.of(player), List.of(UUID.randomUUID()));
        history.record(match);
        return match;
    }

    @Test void testMatchFound() {
        MatchRecord match = record(UUID.randomUUID(), NOW);

        assertEquals(HttpStatus.OK, controller.match(match.matchId()).getStatusCode());
        assertEquals(match, controller.match(match.matchId()).getBody());
    }

    @Test void testMatchNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, controller.match(UUID.randomUUID()).getStatusCode());
    }

    @Test void testHistoryNewestFirst() {
        UUID player = UUID.randomUUID();
        MatchRecord first = record(player, NOW);
        MatchRecord second = record(player, NOW.plusSeconds(60));

        assertEquals(List.of(second, first), controller.history(player));
    }

    @Test void testHistoryEmptyForStranger() {
        assertEquals(List.of(), controller.history(UUID.randomUUID()));
    }
}
