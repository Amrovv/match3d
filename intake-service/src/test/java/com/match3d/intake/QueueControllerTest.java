package com.match3d.intake;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.match3d.common.EntryLeft;
import com.match3d.common.EntryRejected;
import com.match3d.common.EntryQueued;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/** The controller called directly, no server and no broker, so the rules are tested alone. */
class QueueControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-22T14:00:00Z");

    private final QueueRegistry registry = new QueueRegistry();
    private final FakePublisher publisher = new FakePublisher();
    private final RejectionBoard rejections = new RejectionBoard();
    private final MatchBoard board = new MatchBoard();
    private final QueueController controller =
            new QueueController(registry, publisher, Clock.fixed(NOW, ZoneOffset.UTC), rejections, board);

    private ResponseEntity<?> join(List<UUID> members) {
        return controller.join(new JoinRequest(members));
    }

    private static List<UUID> ids(int count) {
        return java.util.stream.Stream.generate(UUID::randomUUID).limit(count).toList();
    }

    @Test void testASoloIsAcceptedUnderTheirOwnIdPos() {
        UUID player = UUID.randomUUID();

        ResponseEntity<?> reply = join(List.of(player));

        assertEquals(HttpStatus.ACCEPTED, reply.getStatusCode(), "A valid solo is accepted");
        assertEquals(new JoinResponse(player), reply.getBody(), "A solo's entry id is their own id");
        assertEquals(List.of(new EntryQueued(player, List.of(player), NOW)), publisher.published,
                "Exactly one EntryQueued goes out, stamped with the clock");
    }

    @Test void testAPartyGetsAFreshEntryId() {
        List<UUID> members = ids(3);

        JoinResponse reply = (JoinResponse) join(members).getBody();

        assertFalse(members.contains(reply.entryId()), "A party's entry id is none of its members' ids");
        assertEquals(reply.entryId(), registry.entryOf(members.get(0)), "The members are recorded under it");
    }

    @Test void testBadSizesAreRefusedNeg() {
        for (List<UUID> members : List.of(List.<UUID>of(), ids(6))) {
            assertEquals(HttpStatus.BAD_REQUEST, join(members).getStatusCode(), members.size() + " members");
        }
        assertEquals(HttpStatus.BAD_REQUEST, join(null).getStatusCode(), "A missing member list");
        assertTrue(publisher.published.isEmpty(), "Nothing refused is published");
    }

    @Test void testARepeatedMemberIsRefusedNeg() {
        UUID twice = UUID.randomUUID();

        assertEquals(HttpStatus.BAD_REQUEST, join(List.of(twice, twice)).getStatusCode());
    }

    @Test void testJoiningTwiceIsAConflictNeg() {
        UUID player = UUID.randomUUID();
        join(List.of(player));

        assertEquals(HttpStatus.CONFLICT, join(List.of(player)).getStatusCode(), "Already queued");
        assertEquals(1, publisher.published.size(), "The second join publishes nothing");
    }

    @Test void testBrokerDownIsUnavailableAndUndone() {
        UUID player = UUID.randomUUID();
        publisher.down = true;

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, join(List.of(player)).getStatusCode());
        assertNull(registry.entryOf(player), "The record is undone, or the player would be stuck queued");

        publisher.down = false;
        assertEquals(HttpStatus.ACCEPTED, join(List.of(player)).getStatusCode(), "So a retry succeeds");
    }

    // leave

    private ResponseEntity<?> leave(UUID entryId) {
        return controller.leave(new LeaveRequest(entryId));
    }

    @Test void testLeavingPublishesAndFreesThePlayerPos() {
        UUID player = UUID.randomUUID();
        join(List.of(player));

        assertEquals(HttpStatus.ACCEPTED, leave(player).getStatusCode(), "A queued entry can leave");
        assertEquals(new EntryLeft(player), publisher.published.get(1), "EntryLeft follows the EntryQueued");
        assertNull(registry.entryOf(player), "The player is free again");
    }

    @Test void testAPartyLeavesByItsEntryIdPos() {
        List<UUID> members = ids(3);
        UUID party = ((JoinResponse) join(members).getBody()).entryId();

        assertEquals(HttpStatus.ACCEPTED, leave(party).getStatusCode(),
                "A party's entry id is no member's id, and it still finds the party");
        members.forEach(m -> assertNull(registry.entryOf(m), "Every member is freed"));
    }

    @Test void testLeavingWithoutAnIdIsABadRequestNeg() {
        assertEquals(HttpStatus.BAD_REQUEST, leave(null).getStatusCode(), "No id to look up");
    }

    @Test void testLeavingAnUnknownEntryIsNotFoundNeg() {
        assertEquals(HttpStatus.NOT_FOUND, leave(UUID.randomUUID()).getStatusCode());
        assertTrue(publisher.published.isEmpty(), "Nothing is published for an entry intake does not hold");
    }

    @Test void testBrokerDownOnLeaveKeepsTheEntryQueued() {
        UUID player = UUID.randomUUID();
        join(List.of(player));
        publisher.down = true;

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, leave(player).getStatusCode());
        assertEquals(player, registry.entryOf(player),
                "Matchmaking never heard, so intake must still hold the entry too");
    }

    @Test void testJoiningAgainClearsAnOldRefusal() {
        UUID player = UUID.randomUUID();
        rejections.record(List.of(player), EntryRejected.Reason.SPREAD_TOO_WIDE);

        join(List.of(player));

        assertNull(rejections.reasonFor(player),
                "A refusal from a previous join must not follow a player who has queued again");
    }

    // status

    @Test void testStatusOfAPlayerWhoNeverQueued() {
        assertEquals(StatusResponse.State.NOT_QUEUED, controller.status(UUID.randomUUID()).state());
    }

    @Test void testStatusOfAQueuedPartyMemberNamesTheEntry() {
        List<UUID> members = ids(3);
        UUID party = ((JoinResponse) join(members).getBody()).entryId();

        StatusResponse status = controller.status(members.get(1));

        assertEquals(StatusResponse.State.QUEUED, status.state(), "A party member is queued");
        assertEquals(party, status.entryId(), "Status names the entry they queued in, not themselves");
    }

    @Test void testStatusOfAMatchedPlayerCarriesTheMatch() {
        UUID player = UUID.randomUUID();
        MatchView match = new MatchView(UUID.randomUUID(), List.of(player), ids(5));
        board.record(match);

        StatusResponse status = controller.status(player);

        assertEquals(StatusResponse.State.MATCHED, status.state());
        assertEquals(match, status.match(), "The player can see their match and both teams");
    }

    @Test void testQueueingAgainOutranksAnOldMatch() {
        UUID player = UUID.randomUUID();
        board.record(new MatchView(UUID.randomUUID(), List.of(player), ids(5)));
        join(List.of(player));

        assertEquals(StatusResponse.State.QUEUED, controller.status(player).state(),
                "What the player is doing now outranks what they did last");
    }

    @Test void testStatusReportsWhyAJoinWasRefused() {
        UUID player = UUID.randomUUID();
        rejections.record(List.of(player), EntryRejected.Reason.SPREAD_TOO_WIDE);

        StatusResponse status = controller.status(player);

        assertEquals(StatusResponse.State.REFUSED, status.state());
        assertEquals(EntryRejected.Reason.SPREAD_TOO_WIDE, status.reason(),
                "A player told 202 and refused later can find out why");
    }

    @Test void testLeavingReturnsThePlayerToNotQueued() {
        UUID player = UUID.randomUUID();
        join(List.of(player));
        leave(player);

        assertEquals(StatusResponse.State.NOT_QUEUED, controller.status(player).state());
    }
}
