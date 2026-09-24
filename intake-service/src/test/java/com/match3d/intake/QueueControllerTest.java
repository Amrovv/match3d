package com.match3d.intake;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.match3d.common.EntryLeft;
import com.match3d.common.EntryRejected;
import com.match3d.common.EntryQueued;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/** The controller called directly over Postgres, no server and no broker. */
class QueueControllerTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-22T14:00:00Z");

    @Autowired private IntakeStore store;

    private final FakePublisher publisher = new FakePublisher();
    private QueueController controller;

    @BeforeEach void wire() {
        controller = new QueueController(store, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UUID entryOf(UUID player) {
        return controller.status(player).entryId();
    }

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
        assertEquals(reply.entryId(), entryOf(members.get(0)), "The members are recorded under it");
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

    @Test void testJoiningTwiceResendsTheEntry() {
        List<UUID> members = ids(2);
        UUID party = ((JoinResponse) join(members).getBody()).entryId();

        ResponseEntity<?> again = join(List.of(members.get(1), members.get(0)));

        assertEquals(HttpStatus.ACCEPTED, again.getStatusCode(), "A retry is not refused");
        assertEquals(new JoinResponse(party), again.getBody(), "It gets the entry it already has");
        assertEquals(publisher.published.get(0), new EntryQueued(party, members, NOW));
        assertEquals(party, ((EntryQueued) publisher.published.get(1)).entryId(), "And publishes it again");
    }

    @Test void testOverlappingAnotherEntryIsAConflictNeg() {
        UUID player = UUID.randomUUID();
        join(List.of(player));

        assertEquals(HttpStatus.CONFLICT, join(List.of(player, UUID.randomUUID())).getStatusCode());
        assertEquals(1, publisher.published.size(), "The refused join publishes nothing");
    }

    @Test void testBrokerDownOnAResendKeepsTheEntry() {
        UUID player = UUID.randomUUID();
        join(List.of(player));
        publisher.down = true;

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, join(List.of(player)).getStatusCode());
        assertEquals(player, entryOf(player), "The entry predates this request, so it is not undone");
    }

    @Test void testBrokerDownIsUnavailableAndUndone() {
        UUID player = UUID.randomUUID();
        publisher.down = true;

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, join(List.of(player)).getStatusCode());
        assertNull(entryOf(player), "The record is undone, or the player would be stuck queued");

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
        assertNull(entryOf(player), "The player is free again");
    }

    @Test void testAPartyLeavesByItsEntryIdPos() {
        List<UUID> members = ids(3);
        UUID party = ((JoinResponse) join(members).getBody()).entryId();

        assertEquals(HttpStatus.ACCEPTED, leave(party).getStatusCode(),
                "A party's entry id is no member's id, and it still finds the party");
        members.forEach(m -> assertNull(entryOf(m), "Every member is freed"));
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
        assertEquals(player, entryOf(player),
                "Matchmaking never heard, so intake must still hold the entry too");
    }

    @Test void testJoiningAgainClearsAnOldRefusal() {
        UUID player = UUID.randomUUID();
        join(List.of(player));
        store.refused(player, EntryRejected.Reason.SPREAD_TOO_WIDE);

        join(List.of(player));

        assertNull(controller.status(player).reason(),
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
        UUID other = UUID.randomUUID();
        join(List.of(player));
        join(List.of(other));
        UUID matchId = UUID.randomUUID();
        store.matched(matchId, List.of(player), List.of(other));

        StatusResponse status = controller.status(player);

        assertEquals(StatusResponse.State.MATCHED, status.state());
        assertEquals(new MatchView(matchId, List.of(player), List.of(other)), status.match(),
                "The player can see their match and both teams");
    }

    @Test void testQueueingAgainOutranksAnOldMatch() {
        UUID player = UUID.randomUUID();
        join(List.of(player));
        store.matched(UUID.randomUUID(), List.of(player), List.of());
        join(List.of(player));

        assertEquals(StatusResponse.State.QUEUED, controller.status(player).state(),
                "What the player is doing now outranks what they did last");
    }

    @Test void testStatusReportsWhyAJoinWasRefused() {
        UUID player = UUID.randomUUID();
        join(List.of(player));
        store.refused(player, EntryRejected.Reason.SPREAD_TOO_WIDE);

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
