package com.match3d.matchmaking;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.match3d.common.EntryLeft;
import com.match3d.common.EntryMatched;
import com.match3d.common.EntryQueued;
import com.match3d.common.EntryRejected;
import com.match3d.core.FairnessHeap;
import com.match3d.core.MatchMaker;
import com.match3d.core.SkillIndex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The consumer and runner wired to a real engine, called directly, no broker
 * and no runner thread. Passes run only when a test calls runPasses.
 */
class EntryConsumerTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private final SkillIndex index = new SkillIndex();
    private final MatchMaker matcher = new MatchMaker(index, new FairnessHeap());
    private final RatingStore ratings = new RatingStore();
    private final EntryBook book = new EntryBook();
    private final MatchHistory history = new MatchHistory();
    private final FakePublisher publisher = new FakePublisher();
    private final MatchRunner runner = new MatchRunner(matcher, index, book, history, publisher,
                                                       Clock.fixed(NOW, ZoneOffset.UTC));
    private final EntryConsumer consumer = new EntryConsumer(matcher, ratings, book, publisher, runner);

    private UUID solo() {
        UUID id = UUID.randomUUID();
        consumer.onQueued(new EntryQueued(id, List.of(id), NOW));
        return id;
    }

    private List<UUID> solos(int count) {
        return Stream.generate(this::solo).limit(count).toList();
    }

    private UUID party(List<UUID> members) {
        UUID id = UUID.randomUUID();
        consumer.onQueued(new EntryQueued(id, members, NOW));
        return id;
    }

    private static List<UUID> ids(int count) {
        return Stream.generate(UUID::randomUUID).limit(count).toList();
    }

    private List<EntryMatched> matched() {
        return publisher.published.stream()
                .filter(EntryMatched.class::isInstance).map(EntryMatched.class::cast).toList();
    }

    @Test void testSoloQueuedPos() {
        UUID id = solo();

        assertTrue(index.contains(id), "A solo is queued under their own id");
        assertEquals(1, index.playerCount());
        assertTrue(publisher.published.isEmpty(), "A successful join sends nothing back");
    }

    @Test void testPartyQueuedAsOneEntry() {
        UUID id = party(ids(3));

        assertTrue(index.contains(id), "A party is queued under the id intake minted");
        assertEquals(1, index.entryCount());
        assertEquals(3, index.playerCount());
    }

    @Test void testDuplicateRejected() {
        UUID id = solo();
        consumer.onQueued(new EntryQueued(id, List.of(id), NOW));

        assertEquals(List.of(new EntryRejected(id, EntryRejected.Reason.DUPLICATE)), publisher.published);
        assertEquals(1, index.playerCount(), "A duplicate must not queue twice");
    }

    @Test void testSpreadTooWideRejected() {
        List<UUID> members = ids(2);
        ratings.set(members.get(0), 1);
        ratings.set(members.get(1), 5000);
        UUID id = party(members);

        assertEquals(List.of(new EntryRejected(id, EntryRejected.Reason.SPREAD_TOO_WIDE)), publisher.published);
        assertFalse(index.contains(id), "A refused party is never queued");
        assertNull(book.entryOf(members.get(0)), "Nor recorded");
    }

    @Test void testLeaveWithdraws() {
        UUID id = solo();
        consumer.onLeft(new EntryLeft(id));

        assertFalse(index.contains(id));
        assertNull(book.entryOf(id), "A withdrawn entry is forgotten");
    }

    @Test void testLeaveUnknownIgnored() {
        consumer.onLeft(new EntryLeft(UUID.randomUUID()));
        assertTrue(publisher.published.isEmpty(), "A leave for nothing held sends nothing");
    }

    @Test void testTenSolosMatched() {
        List<UUID> solos = solos(10);
        runner.runPasses();

        assertEquals(1, matched().size(), "Ten solos at one rating form one lobby");
        EntryMatched lobby = matched().get(0);
        assertEquals(5, lobby.teamA().size());
        assertEquals(5, lobby.teamB().size());
        assertTrue(Stream.concat(lobby.teamA().stream(), lobby.teamB().stream()).toList().containsAll(solos));
        assertEquals(0, index.playerCount(), "Everyone matched left the engine");
        assertNull(book.entryOf(solos.get(0)), "And the book");
    }

    @Test void testPartyMatchedAsItsEntryId() {
        List<UUID> members = ids(5);
        UUID partyId = party(members);
        solos(5);
        runner.runPasses();

        EntryMatched lobby = matched().get(0);
        List<UUID> all = Stream.concat(lobby.teamA().stream(), lobby.teamB().stream()).toList();
        assertEquals(6, all.size(), "Five members travel as one entry id, plus five solos");
        assertTrue(all.contains(partyId), "Intake names the party by its entry id");
        assertFalse(all.contains(members.get(0)), "Not by its members");
    }

    @Test void testMatchRecordedByPlayer() {
        List<UUID> members = ids(5);
        party(members);
        solos(5);
        runner.runPasses();

        UUID matchId = matched().get(0).matchId();
        assertTrue(history.match(matchId).isPresent(), "The match is queryable by id");
        assertEquals(matchId, history.historyOf(members.get(0)).get(0).matchId(),
                "A party member's history names the match, by their own id");
    }

    @Test void testNinePlayersSkipPass() {
        solos(9);
        runner.runPasses();

        assertTrue(matched().isEmpty());
        assertEquals(9, index.playerCount(), "Below ten no pass runs, so no anchor is cooled");
    }
}
