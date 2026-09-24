package com.match3d.intake;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.match3d.common.EntryMatched;
import com.match3d.common.EntryQueued;
import com.match3d.common.EntryRejected;
import com.match3d.common.EventJson;
import com.match3d.common.MatchEnded;
import com.match3d.common.MatchmakingStarted;

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
    private final FakePublisher publisher = new FakePublisher();
    private ResultListener listener;

    @BeforeEach void wire() {
        listener = new ResultListener(store, publisher);
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

    @Test void testRestartRequeuesWhatIsStillQueued() {
        UUID party = UUID.randomUUID();
        List<UUID> members = queueParty(party, 3);
        UUID solo = queueSolo();

        listener.onStarted(new MatchmakingStarted(NOW.plusSeconds(600)));

        List<EntryQueued> sent = publisher.published.stream().map(EntryQueued.class::cast).toList();
        assertEquals(2, sent.size(), "Every queued entry is sent again");
        EntryQueued partyJoin = sent.stream().filter(j -> j.entryId().equals(party)).findFirst().orElseThrow();
        assertEquals(Set.copyOf(members), Set.copyOf(partyJoin.memberIds()), "With all its members");
        assertEquals(NOW, partyJoin.queuedAt(), "And its original queue time, so it keeps its place");
        assertTrue(sent.stream().anyMatch(j -> j.entryId().equals(solo)));
    }

    @Test void testRestartSkipsALobbyPublishedBeforeIt() {
        UUID matchedSolo = queueSolo();
        UUID other = queueSolo();
        UUID waiting = queueSolo();
        listener.onMatched(new EntryMatched(UUID.randomUUID(), List.of(matchedSolo), List.of(other)));

        listener.onStarted(new MatchmakingStarted(NOW));

        assertEquals(List.of(new EntryQueued(waiting, List.of(waiting), NOW)), publisher.published,
                "Only the entry still queued is sent; the matched ones were seen first on the same queue");
    }

    @Test void testRestartWithNothingQueuedSendsNothing() {
        listener.onStarted(new MatchmakingStarted(NOW));

        assertTrue(publisher.published.isEmpty(), "A first start finds an empty table");
    }

    @Test void testRestartWithBrokerDownDoesNotThrow() {
        queueSolo();
        publisher.down = true;

        assertDoesNotThrow(() -> listener.onStarted(new MatchmakingStarted(NOW)), "Logged as stranded instead");
    }
}
