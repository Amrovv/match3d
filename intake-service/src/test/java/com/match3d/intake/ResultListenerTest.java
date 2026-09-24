package com.match3d.intake;

import java.util.List;
import java.util.UUID;

import com.match3d.common.EntryMatched;
import com.match3d.common.EntryRejected;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What intake does with what matchmaking sends back, called directly, no broker. */
class ResultListenerTest {

    private final QueueRegistry registry = new QueueRegistry();
    private final MatchBoard board = new MatchBoard();
    private final RejectionBoard rejections = new RejectionBoard();
    private final ResultListener listener = new ResultListener(registry, board, rejections);

    /** A party of the given size, queued, and the entry id it queued under. */
    private UUID queueParty(int size) {
        UUID entry = UUID.randomUUID();
        List<UUID> members = java.util.stream.Stream.generate(UUID::randomUUID).limit(size).toList();
        registry.tryQueue(entry, members);
        return entry;
    }

    /** A solo, queued under their own id. */
    private UUID queueSolo() {
        UUID player = UUID.randomUUID();
        registry.tryQueue(player, List.of(player));
        return player;
    }

    @Test void testAMatchIsRecordedAgainstEveryPlayerPos() {
        UUID party = queueParty(3);
        List<UUID> partyMembers = registry.membersOf(party);
        UUID solo = queueSolo();

        listener.onMatched(new EntryMatched(UUID.randomUUID(), List.of(party), List.of(solo)));

        for (UUID player : partyMembers) {
            assertNotNull(board.matchOf(player), "Every player of a matched party can see the match");
        }
        assertEquals(board.matchOf(solo), board.matchOf(partyMembers.get(0)), "Both sides share one match");
    }

    @Test void testTeamsAreKeptApartAsPlayers() {
        UUID party = queueParty(2);
        List<UUID> partyMembers = registry.membersOf(party);
        UUID solo = queueSolo();
        UUID matchId = UUID.randomUUID();

        listener.onMatched(new EntryMatched(matchId, List.of(party), List.of(solo)));
        MatchView view = board.matchOf(solo);

        assertEquals(matchId, view.matchId(), "The match keeps its id");
        assertEquals(partyMembers, view.teamA(), "Team A is the party's members, not its entry id");
        assertEquals(List.of(solo), view.teamB(), "Team B is the solo");
    }

    @Test void testMatchedEntriesAreFreedToQueueAgain() {
        UUID party = queueParty(4);
        List<UUID> partyMembers = registry.membersOf(party);

        listener.onMatched(new EntryMatched(UUID.randomUUID(), List.of(party), List.of()));

        assertFalse(registry.contains(party), "A matched entry is no longer queued");
        partyMembers.forEach(m -> assertNull(registry.entryOf(m), "Nor are its members"));
    }

    @Test void testAnEntryIntakeNoLongerHoldsIsSkipped() {
        // The party left just before the lobby formed, so intake has already
        // forgotten it. The rest of the lobby must still be recorded.
        UUID left = UUID.randomUUID();
        UUID solo = queueSolo();

        listener.onMatched(new EntryMatched(UUID.randomUUID(), List.of(left), List.of(solo)));

        assertEquals(List.of(), board.matchOf(solo).teamA(), "An unknown entry contributes no players");
        assertEquals(List.of(solo), board.matchOf(solo).teamB(), "The rest of the lobby is still recorded");
    }

    @Test void testASpreadRejectionIsKeptAndTheEntryForgotten() {
        UUID party = queueParty(2);
        List<UUID> partyMembers = registry.membersOf(party);

        listener.onRejected(new EntryRejected(party, EntryRejected.Reason.SPREAD_TOO_WIDE));

        partyMembers.forEach(m -> assertEquals(EntryRejected.Reason.SPREAD_TOO_WIDE, rejections.reasonFor(m),
                "Each member can be told why their join was refused"));
        assertFalse(registry.contains(party), "The entry was never queued in matchmaking, so intake drops it");
    }

    @Test void testADuplicateRejectionChangesNothing() {
        // Almost always a redelivered message, so the entry really is queued.
        UUID solo = queueSolo();

        listener.onRejected(new EntryRejected(solo, EntryRejected.Reason.DUPLICATE));

        assertTrue(registry.contains(solo), "A duplicate must not drop an entry that is genuinely queued");
        assertNull(rejections.reasonFor(solo), "And nothing to report to the player");
    }

    @Test void testMatchEndedFreesEveryPlayer() {
        UUID party = queueParty(2);
        List<UUID> partyMembers = registry.membersOf(party);
        UUID solo = queueSolo();
        UUID matchId = UUID.randomUUID();
        listener.onMatched(new EntryMatched(matchId, List.of(party), List.of(solo)));

        board.end(matchId);

        assertNull(board.matchOf(solo), "An ended match no longer shows");
        partyMembers.forEach(m -> assertNull(board.matchOf(m), "For any player in it"));
    }

    @Test void testMatchEndedKeepsANewerMatch() {
        UUID solo = queueSolo();
        UUID first = UUID.randomUUID();
        listener.onMatched(new EntryMatched(first, List.of(solo), List.of(queueSolo())));
        registry.tryQueue(solo, List.of(solo));
        UUID second = UUID.randomUUID();
        listener.onMatched(new EntryMatched(second, List.of(solo), List.of(queueSolo())));

        board.end(first);

        assertEquals(second, board.matchOf(solo).matchId(), "Ending an old match leaves the current one");
    }
}
