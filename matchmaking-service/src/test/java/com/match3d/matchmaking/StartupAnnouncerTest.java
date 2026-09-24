package com.match3d.matchmaking;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.match3d.common.MatchmakingStarted;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The announcement, called directly. Spring calls it once when the service is ready. */
class StartupAnnouncerTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Test void testAnnouncesTheStart() {
        FakePublisher publisher = new FakePublisher();

        new StartupAnnouncer(publisher, Clock.fixed(NOW, ZoneOffset.UTC)).announce();

        assertEquals(List.of(new MatchmakingStarted(NOW)), publisher.published);
    }

    @Test void testBrokerDownDoesNotStopTheService() {
        EventPublisher broken = event -> { throw new IllegalStateException("broker down"); };

        assertDoesNotThrow(() -> new StartupAnnouncer(broken, Clock.fixed(NOW, ZoneOffset.UTC)).announce());
    }
}
