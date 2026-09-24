package com.match3d.intake;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.match3d.common.EntryQueued;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Entries matchmaking never confirmed are sent again; confirmed and fresh ones are left alone. */
class SweeperTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired private IntakeStore store;
    @Autowired private JdbcTemplate jdbc;

    private final FakePublisher publisher = new FakePublisher();
    private Sweeper sweeper;

    @BeforeEach void wire() {
        sweeper = new Sweeper(store, publisher);
    }

    private void sentSecondsAgo(UUID entryId, int seconds) {
        jdbc.update("update entries set sent_at = now() - make_interval(secs => ?) where id = ?", seconds, entryId);
    }

    private UUID solo() {
        UUID id = UUID.randomUUID();
        store.join(id, List.of(id), NOW);
        return id;
    }

    @Test void testUnconfirmedEntryIsSentAgain() {
        UUID party = UUID.randomUUID();
        List<UUID> members = Stream.generate(UUID::randomUUID).limit(3).toList();
        store.join(party, members, NOW);
        sentSecondsAgo(party, 31);

        sweeper.sweep();

        assertEquals(1, publisher.published.size());
        EntryQueued join = (EntryQueued) publisher.published.get(0);
        assertEquals(party, join.entryId(), "The same entry");
        assertEquals(Set.copyOf(members), Set.copyOf(join.memberIds()), "With all its members");
        assertEquals(NOW, join.queuedAt(), "And its original queue time");
    }

    @Test void testConfirmedEntryIsLeftAlone() {
        UUID player = solo();
        store.accepted(player, 2500);
        sentSecondsAgo(player, 120);

        sweeper.sweep();

        assertTrue(publisher.published.isEmpty(), "Matchmaking holds it");
    }

    @Test void testRecentlySentEntryIsLeftAlone() {
        UUID player = solo();
        sentSecondsAgo(player, 20);

        sweeper.sweep();

        assertTrue(publisher.published.isEmpty(), "It may still be on its way");
    }

    @Test void testSentOnceThenWaitsAgain() {
        UUID player = solo();
        sentSecondsAgo(player, 31);

        sweeper.sweep();
        sweeper.sweep();

        assertEquals(1, publisher.published.size(), "Marked sent, so the next sweep waits another 30 seconds");
    }

    @Test void testBrokerDownIsRetriedLater() {
        UUID player = solo();
        sentSecondsAgo(player, 31);
        publisher.down = true;

        assertDoesNotThrow(() -> sweeper.sweep());

        publisher.down = false;
        sentSecondsAgo(player, 31);
        sweeper.sweep();
        assertEquals(1, publisher.published.size(), "A later sweep sends it once the broker is back");
    }
}
