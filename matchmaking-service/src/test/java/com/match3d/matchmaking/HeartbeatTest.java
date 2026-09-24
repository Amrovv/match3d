package com.match3d.matchmaking;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.match3d.common.MatchmakingAlive;
import com.match3d.common.WaitBand;
import com.match3d.core.Lobby;
import com.match3d.core.Player;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** One beat, called directly. Spring calls it every ten seconds. */
class HeartbeatTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired private MatchHistory history;
    @Autowired private RatingStore ratings;

    private Player player(int rating, Instant queuedAt) {
        UUID id = UUID.randomUUID();
        ratings.create(id);
        return new Player(id, rating, queuedAt);
    }

    @Test void testBeatCarriesTheBands() {
        history.record(UUID.randomUUID(), NOW.minusSeconds(30), new Lobby(
                List.of(player(2500, NOW.minusSeconds(60))), List.of(player(2500, NOW.minusSeconds(60)))));
        FakePublisher publisher = new FakePublisher();

        new Heartbeat(history, publisher, Clock.fixed(NOW, ZoneOffset.UTC)).beat();

        assertEquals(List.of(new MatchmakingAlive(NOW, List.of(new WaitBand(25, 60, 2)))), publisher.published);
    }

    @Test void testBeatWithAnEmptyHour() {
        FakePublisher publisher = new FakePublisher();

        new Heartbeat(history, publisher, Clock.fixed(NOW, ZoneOffset.UTC)).beat();

        assertEquals(List.of(new MatchmakingAlive(NOW, List.of())), publisher.published, "Alive, with nothing to average");
    }

    @Test void testBrokerDownDoesNotStopTheSchedule() {
        EventPublisher broken = event -> { throw new IllegalStateException("broker down"); };

        assertDoesNotThrow(() -> new Heartbeat(history, broken, Clock.fixed(NOW, ZoneOffset.UTC)).beat());
    }
}
