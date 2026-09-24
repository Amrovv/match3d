package com.match3d.intake;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.match3d.common.EntryMatched;
import com.match3d.common.EntryRejected;
import com.match3d.common.EventJson;
import com.match3d.common.MatchEnded;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** What intake does with what matchmaking sends back, called directly over Postgres, no broker. */
class ResultListenerTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired private IntakeStore store;
    private ResultListener listener;

    @BeforeEach void wire() {
        listener = new ResultListener(store);
    }

    private List<UUID> queueParty(UUID entryId, int size) {
        List<UUID> members = Stream.generate(UUID::randomUUID).limit(size).toList();
        store.join(entryId, members, NOW);
        return members;
    }

    private UUID queueSolo() {
        UUID player = UUID.randomUUID();
        store.join(player, List.of(player), NOW);
        return player;
    }

    private StatusResponse status(UUID player) {
        return store.status(player);
    }

    @Test void testTeamsAreKeptApartAsPlayers() {
        UUID party = UUID.randomUUID();
        List<UUID> members = queueParty(party, 2);
        UUID solo = queueSolo();
        UUID matchId = UUID.randomUUID();

        listener.onMatched(new EntryMatched(matchId, List.of(party), List.of(solo)));
        MatchView view = status(solo).match();

        assertEquals(matchId, view.matchId(), "The match keeps its id");
        assertEquals(members.stream().sorted().toList(), view.teamA().stream().sorted().toList(),
                "Team A is the party's members, not its entry id");
        assertEquals(List.of(solo), view.teamB(), "Team B is the solo");
        assertEquals(view.matchId(), status(members.get(0)).match().matchId(), "Every player sees the same match");
        assertFalse(store.isQueued(party), "A matched entry is no longer queued");
    }

    @Test void testRefusalIsKeptAndTheEntryForgotten() {
        UUID party = UUID.randomUUID();
        List<UUID> members = queueParty(party, 2);

        listener.onRejected(new EntryRejected(party, EntryRejected.Reason.SPREAD_TOO_WIDE));

        members.forEach(m -> assertEquals(EntryRejected.Reason.SPREAD_TOO_WIDE, status(m).reason(),
                "Each member can be told why their join was refused"));
        assertFalse(store.isQueued(party), "Never queued in matchmaking, so intake drops it");
    }

    @Test void testUnknownPlayerRefusalHandledAlike() {
        UUID solo = queueSolo();

        listener.onRejected(new EntryRejected(solo, EntryRejected.Reason.UNKNOWN_PLAYER));

        assertEquals(EntryRejected.Reason.UNKNOWN_PLAYER, status(solo).reason());
    }

    @Test void testADuplicateRejectionChangesNothing() {
        // Almost always a redelivered message or a resent join, so the entry really is queued.
        UUID solo = queueSolo();

        listener.onRejected(new EntryRejected(solo, EntryRejected.Reason.DUPLICATE));

        assertEquals(StatusResponse.State.QUEUED, status(solo).state(),
                "A duplicate must not drop an entry that is genuinely queued");
    }

    @Test void testMatchEndedMessageFreesThePlayers() {
        UUID solo = queueSolo();
        UUID other = queueSolo();
        UUID matchId = UUID.randomUUID();
        listener.onMatched(new EntryMatched(matchId, List.of(solo), List.of(other)));

        MessageProperties props = new MessageProperties();
        props.setType("MatchEnded");
        listener.onMessage(new Message(EventJson.toBytes(new MatchEnded(matchId)), props));

        assertEquals(StatusResponse.State.NOT_QUEUED, status(solo).state(), "Routed by type to the store");
        assertEquals(StatusResponse.State.NOT_QUEUED, status(other).state());
    }

    @Test void testUnknownMessageTypeThrows() {
        MessageProperties props = new MessageProperties();
        props.setType("EntryTeleported");

        assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(new Message("{}".getBytes(StandardCharsets.UTF_8), props)));
    }
}
