package com.match3d.intake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import com.match3d.common.EntryRejected;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** What intake records about each player, over Postgres, including joins racing across copies. */
class IntakeStoreTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired private IntakeStore store;
    @Autowired private EntryRepository entries;
    @Autowired private JdbcTemplate jdbc;

    private static List<UUID> ids(int count) {
        return Stream.generate(UUID::randomUUID).limit(count).toList();
    }

    private StatusResponse.State state(UUID player) {
        return store.status(player).state();
    }

    private UUID solo() {
        UUID id = UUID.randomUUID();
        store.join(id, List.of(id), NOW);
        return id;
    }

    @Test void testSoloQueuedUnderOwnId() {
        UUID player = UUID.randomUUID();

        IntakeStore.Joined joined = store.join(player, List.of(player), NOW);

        assertEquals(new IntakeStore.Joined(player, NOW, false), joined);
        assertEquals(player, store.status(player).entryId(), "A solo's entry is themselves");
    }

    @Test void testEveryPartyMemberQueuedInTheParty() {
        UUID party = UUID.randomUUID();
        List<UUID> members = ids(3);

        store.join(party, members, NOW);

        members.forEach(m -> assertEquals(party, store.status(m).entryId()));
    }

    @Test void testSameMembersAgainResendsOriginal() {
        UUID party = UUID.randomUUID();
        List<UUID> members = ids(2);
        store.join(party, members, NOW);

        IntakeStore.Joined again = store.join(UUID.randomUUID(), List.of(members.get(1), members.get(0)),
                NOW.plusSeconds(30));

        assertEquals(new IntakeStore.Joined(party, NOW, true), again,
                "The original entry and queue time, whatever the order of members");
    }

    @Test void testOverlapWithAnotherEntryRefusedWhole() {
        UUID queued = solo();
        UUID free = UUID.randomUUID();
        UUID party = UUID.randomUUID();

        assertThrows(AlreadyQueuedException.class, () -> store.join(party, List.of(free, queued), NOW));
        assertEquals(StatusResponse.State.NOT_QUEUED, state(free), "Nobody from a refused party is recorded");
        assertFalse(store.isQueued(party), "And no entry is left behind");
    }

    @Test void testSubsetOfAPartyRefused() {
        List<UUID> members = ids(3);
        store.join(UUID.randomUUID(), members, NOW);

        assertThrows(AlreadyQueuedException.class,
                () -> store.join(UUID.randomUUID(), members.subList(0, 2), NOW),
                "Only the exact same members resend");
    }

    @Test void testForgetFreesEveryMember() {
        UUID party = UUID.randomUUID();
        List<UUID> members = ids(2);
        store.join(party, members, NOW);

        store.forget(party);

        assertFalse(store.isQueued(party));
        members.forEach(m -> assertEquals(StatusResponse.State.NOT_QUEUED, state(m)));
        assertFalse(store.join(UUID.randomUUID(), members, NOW).resent(), "So they can queue afresh");
    }

    @Test void testMatchedMovesMembersAndDropsEntries() {
        UUID party = UUID.randomUUID();
        List<UUID> members = ids(2);
        store.join(party, members, NOW);
        UUID solo = solo();
        UUID matchId = UUID.randomUUID();

        store.matched(matchId, List.of(party), List.of(solo));

        MatchView view = store.status(solo).match();
        assertEquals(matchId, view.matchId());
        assertEquals(members.stream().sorted().toList(), view.teamA().stream().sorted().toList());
        assertEquals(List.of(solo), view.teamB());
        assertFalse(store.isQueued(party), "A matched entry is no longer queued");
    }

    @Test void testMatchedSkipsAnEntryThatLeft() {
        UUID solo = solo();
        UUID matchId = UUID.randomUUID();

        store.matched(matchId, List.of(UUID.randomUUID()), List.of(solo));

        assertEquals(List.of(), store.status(solo).match().teamA(), "An entry no longer held adds nobody");
        assertEquals(List.of(solo), store.status(solo).match().teamB(), "The rest of the lobby is recorded");
    }

    @Test void testRefusedKeepsReasonAndDropsEntry() {
        UUID party = UUID.randomUUID();
        List<UUID> members = ids(2);
        store.join(party, members, NOW);

        store.refused(party, EntryRejected.Reason.UNKNOWN_PLAYER);

        members.forEach(m -> assertEquals(EntryRejected.Reason.UNKNOWN_PLAYER, store.status(m).reason()));
        assertFalse(store.isQueued(party));
    }

    @Test void testJoiningAgainClearsARefusal() {
        UUID player = solo();
        store.refused(player, EntryRejected.Reason.SPREAD_TOO_WIDE);

        store.join(player, List.of(player), NOW);

        assertEquals(StatusResponse.State.QUEUED, state(player));
    }

    @Test void testEndedFreesAMatchButKeepsANewerOne() {
        UUID player = solo();
        UUID first = UUID.randomUUID();
        store.matched(first, List.of(player), List.of(solo()));
        store.join(player, List.of(player), NOW);
        UUID second = UUID.randomUUID();
        store.matched(second, List.of(player), List.of(solo()));

        store.ended(first);

        assertEquals(second, store.status(player).match().matchId(), "Ending an old match leaves the current one");
        store.ended(second);
        assertEquals(StatusResponse.State.NOT_QUEUED, state(player));
    }

    @Test void testSchemaRefusesTwoStatesAtOnce() {
        UUID player = solo();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update players set rejection = 'DUPLICATE' where id = ?", player),
                "Queued and refused together breaks the check");
    }

    /** Outside the test transaction, so each thread commits on its own connection. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testConcurrentJoinsForOnePlayerAdmitOne() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 50; round++) {
                UUID player = UUID.randomUUID();
                CountDownLatch start = new CountDownLatch(1);
                List<UUID> parties = new ArrayList<>();
                List<Future<Boolean>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    UUID party = UUID.randomUUID();
                    parties.add(party);
                    Callable<Boolean> call = () -> {
                        start.await();
                        try {
                            store.join(party, List.of(player, UUID.randomUUID()), NOW);
                            return true;
                        } catch (AlreadyQueuedException e) {
                            return false;
                        }
                    };
                    results.add(pool.submit(call));
                }
                start.countDown();

                int admitted = 0;
                for (Future<Boolean> r : results) if (r.get()) admitted++;
                assertEquals(1, admitted, "One join wins, round " + round);
                assertEquals(1, entries.findAllById(parties).size(), "Losers roll back their entry, round " + round);
            }
        } finally {
            pool.shutdownNow();
            wipe();
        }
    }

    /** Two parties sharing members, listed in opposite orders: one wins, and nothing deadlocks. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testOverlappingPartiesInOppositeOrderDoNotDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
                CountDownLatch start = new CountDownLatch(1);
                Callable<Boolean> one = () -> tryJoin(start, List.of(p1, p2, UUID.randomUUID()));
                Callable<Boolean> two = () -> tryJoin(start, List.of(p2, p1, UUID.randomUUID()));
                Future<Boolean> a = pool.submit(one);
                Future<Boolean> b = pool.submit(two);
                start.countDown();

                assertTrue(a.get() ^ b.get(), "Exactly one party queues, round " + round);
            }
        } finally {
            pool.shutdownNow();
            wipe();
        }
    }

    private boolean tryJoin(CountDownLatch start, List<UUID> members) throws InterruptedException {
        start.await();
        try {
            store.join(UUID.randomUUID(), members, NOW);
            return true;
        } catch (AlreadyQueuedException e) {
            return false;
        }
    }
}
