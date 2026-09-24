package com.match3d.matchmaking;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.match3d.common.EntryAccepted;
import com.match3d.common.EntryLeft;
import com.match3d.common.EntryMatched;
import com.match3d.common.EntryQueued;
import com.match3d.common.EntryRejected;
import com.match3d.core.FairnessHeap;
import com.match3d.core.MatchMaker;
import com.match3d.core.Player;
import com.match3d.core.QueueEntry;
import com.match3d.core.SkillIndex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The consumer and runner wired to a real engine and Postgres, called
 * directly, no broker and no runner thread. Passes run only when a test calls
 * runPasses. The engine is built per test; the stores are Spring's.
 */
class EntryConsumerTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private final SkillIndex index = new SkillIndex();
    private final MatchMaker matcher = new MatchMaker(index, new FairnessHeap());
    private final EntryBook book = new EntryBook();
    private final FakePublisher publisher = new FakePublisher();

    @Autowired private RatingStore ratings;
    @Autowired private MatchHistory history;
    @Autowired private PlayerMatchRepository playerMatches;
    @Autowired private MatchRepository matches;

    private MatchRunner runner;
    private EntryConsumer consumer;

    @BeforeEach void wire() {
        runner = new MatchRunner(matcher, index, book, history, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
        consumer = new EntryConsumer(matcher, ratings, book, publisher, runner);
    }

    private UUID solo() {
        UUID id = UUID.randomUUID();
        ratings.create(id);
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

    /** Players with a players row, as matchmaking requires. */
    private List<UUID> ids(int count) {
        List<UUID> ids = Stream.generate(UUID::randomUUID).limit(count).toList();
        ids.forEach(ratings::create);
        return ids;
    }

    /** The entry the engine holds under this id. */
    private QueueEntry queued(UUID id) {
        return index.entriesInRange(1, 5000).flatMap(Set::stream)
                .filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    private List<EntryMatched> matched() {
        return publisher.published.stream()
                .filter(EntryMatched.class::isInstance).map(EntryMatched.class::cast).toList();
    }

    @Test void testSoloQueuedPos() {
        UUID id = solo();

        assertTrue(index.contains(id), "A solo is queued under their own id");
        assertEquals(1, index.playerCount());
        assertEquals(List.of(new EntryAccepted(id, 2500)), publisher.published,
                "A successful join is confirmed, with the rating it is queued at");
    }

    @Test void testPartyQueuedAsOneEntry() {
        UUID id = party(ids(3));

        assertTrue(index.contains(id), "A party is queued under the id intake created");
        assertEquals(1, index.entryCount());
        assertEquals(3, index.playerCount());
    }

    @Test void testDuplicateRejected() {
        UUID id = solo();
        consumer.onQueued(new EntryQueued(id, List.of(id), NOW));

        assertEquals(List.of(new EntryAccepted(id, 2500), new EntryRejected(id, EntryRejected.Reason.DUPLICATE)),
                publisher.published, "Accepted once, then refused as a duplicate");
        assertEquals(1, index.playerCount(), "A duplicate must not queue twice");
    }

    @Test void testSpreadTooWideRejected() {
        List<UUID> members = ids(2);
        rate(members.get(0), 1);
        rate(members.get(1), 5000);
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

    @Test void testUnknownSoloRejected() {
        UUID id = UUID.randomUUID();
        consumer.onQueued(new EntryQueued(id, List.of(id), NOW));

        assertEquals(List.of(new EntryRejected(id, EntryRejected.Reason.UNKNOWN_PLAYER)), publisher.published);
        assertFalse(index.contains(id), "An unknown player is never queued");
        assertNull(book.entryOf(id), "Nor recorded");
    }

    @Test void testPartyWithUnknownMemberRejected() {
        List<UUID> members = new ArrayList<>(ids(2));
        members.add(UUID.randomUUID());
        UUID id = party(members);

        assertEquals(List.of(new EntryRejected(id, EntryRejected.Reason.UNKNOWN_PLAYER)), publisher.published);
        assertFalse(index.contains(id), "One unknown member refuses the whole party");
        assertNull(book.entryOf(members.get(0)), "Known members are not recorded either");
    }

    @Test void testPartyMembersKeepOwnRatings() {
        List<UUID> members = ids(3);
        Map<UUID, Integer> expected = Map.of(members.get(0), 2000, members.get(1), 2100, members.get(2), 2200);
        expected.forEach(this::rate);
        UUID id = party(members);

        Map<UUID, Integer> held = queued(id).members().stream()
                .collect(Collectors.toMap(Player::id, Player::rating));
        assertEquals(expected, held, "Each member is queued at their own stored rating");
    }

    @Test void testPlayerRowsStoreWhatEngineUsed() {
        Map<UUID, Integer> rating = new HashMap<>();
        Map<UUID, Instant> queuedAt = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            UUID id = UUID.randomUUID();
            ratings.create(id);
            rate(id, 2500 + i);
            Instant at = NOW.minusSeconds(i);
            consumer.onQueued(new EntryQueued(id, List.of(id), at));
            rating.put(id, 2500 + i);
            queuedAt.put(id, at);
        }
        runner.runPasses();

        List<PlayerMatchRow> rows = playerMatches.findByKeyMatchIdIn(List.of(matched().get(0).matchId()));
        assertEquals(10, rows.size());
        for (PlayerMatchRow row : rows) {
            assertEquals(rating.get(row.getPlayerId()), row.getRatingBefore(), "Rating before is the queued rating");
            assertEquals(queuedAt.get(row.getPlayerId()), row.getQueuedAt(), "Queued at is the join time");
        }
    }

    @Test void testRefusedThenRegisteredAccepted() {
        UUID id = UUID.randomUUID();
        consumer.onQueued(new EntryQueued(id, List.of(id), NOW));
        ratings.create(id);
        consumer.onQueued(new EntryQueued(id, List.of(id), NOW));

        assertEquals(List.of(new EntryRejected(id, EntryRejected.Reason.UNKNOWN_PLAYER), new EntryAccepted(id, 2500)),
                publisher.published, "The first join is refused, the second accepted");
        assertTrue(index.contains(id), "A refusal leaves nothing behind that blocks a retry");
    }

    @Test void testUnknownEventTypeThrows() {
        MessageProperties props = new MessageProperties();
        props.setType("EntryTeleported");

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(new Message(new byte[0], props)));
    }

    @Test void testFailedAnnounceReturnsEntriesAndForgetsTheMatch() {
        List<UUID> members = ids(5);
        UUID partyId = party(members);
        List<UUID> solos = solos(5);
        publisher.down = true;

        runner.runPasses();

        assertEquals(0, matches.count(), "A lobby never announced leaves no match behind");
        assertEquals(10, index.playerCount(), "All ten are back in the engine");
        assertEquals(6, index.entryCount(), "The party is back whole, as one entry");
        assertTrue(index.contains(partyId));
        assertEquals(NOW, queued(partyId).queuedAt(), "Keeping its original queue time");
        assertEquals(partyId, book.entryOf(members.get(0)), "And the book still maps its members");

        publisher.down = false;
        runner.runPasses();

        assertEquals(1, matched().size(), "Once the broker is back, the lobby forms and is announced");
        assertEquals(1, matches.count());
        assertTrue(solos.stream().allMatch(id -> book.entryOf(id) == null), "And only then forgotten");
    }

    /** Only the first publish fails, so a round that carried on would announce the reformed lobby. */
    @Test void testFailedAnnounceStopsThePasses() {
        solos(20);
        publisher.failNext = 1;

        runner.runPasses();

        assertTrue(matched().isEmpty(), "One failure ends the round instead of retrying the same lobby");
        assertEquals(20, index.playerCount());
    }

    @Test void testPartyAcceptedAtItsOwnRating() {
        List<UUID> members = ids(2);
        rate(members.get(0), 2000);
        rate(members.get(1), 3000);
        UUID id = party(members);

        assertEquals(List.of(new EntryAccepted(id, queued(id).rating())), publisher.published,
                "A party is confirmed at the rating the engine matches it on");
    }
}
