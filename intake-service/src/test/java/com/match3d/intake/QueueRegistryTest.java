package com.match3d.intake;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueueRegistryTest {

    private final QueueRegistry registry = new QueueRegistry();

    @Test void testASoloIsQueuedUnderTheirOwnIdPos() {
        UUID player = UUID.randomUUID();

        assertTrue(registry.tryQueue(player, List.of(player)), "A player not yet queued can queue");
        assertEquals(player, registry.entryOf(player), "A solo's entry is themselves");
    }

    @Test void testEveryPartyMemberMapsToTheParty() {
        UUID party = UUID.randomUUID();
        List<UUID> members = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        registry.tryQueue(party, members);

        members.forEach(m -> assertEquals(party, registry.entryOf(m), "Each member is queued in the party"));
    }

    @Test void testAPartyWithOneQueuedMemberIsRefusedWholeNeg() {
        UUID solo = UUID.randomUUID();
        registry.tryQueue(solo, List.of(solo));
        UUID stranger = UUID.randomUUID();
        UUID party = UUID.randomUUID();

        assertFalse(registry.tryQueue(party, List.of(stranger, solo)), "One member already queued refuses the party");
        assertNull(registry.entryOf(stranger), "Nobody from a refused party is recorded, not even the free member");
        assertFalse(registry.remove(party), "A refused party leaves no entry behind");
    }

    @Test void testTheSameEntryIdCannotQueueTwiceNeg() {
        UUID party = UUID.randomUUID();
        registry.tryQueue(party, List.of(UUID.randomUUID(), UUID.randomUUID()));

        assertFalse(registry.tryQueue(party, List.of(UUID.randomUUID(), UUID.randomUUID())),
                "An entry id is used once, new members cannot be added to it");
    }

    @Test void testRemoveFreesEveryMember() {
        UUID party = UUID.randomUUID();
        List<UUID> members = List.of(UUID.randomUUID(), UUID.randomUUID());
        registry.tryQueue(party, members);

        assertTrue(registry.remove(party), "A queued entry can be removed");
        members.forEach(m -> assertNull(registry.entryOf(m), "Every member is free again"));
        assertTrue(registry.tryQueue(UUID.randomUUID(), members), "So they can queue again");
    }

    @Test void testRemoveUnknownEntryNeg() {
        assertFalse(registry.remove(UUID.randomUUID()), "An entry never queued cannot be removed");
    }

    @Test void testConcurrentJoinsForOnePlayerAdmitExactlyOne() throws InterruptedException {
        // A double click, many times over: each join carries a fresh entry id,
        // so only the registry's lock stops the player queueing twice. The gap
        // between check and record is tiny, so one race per run proves nothing;
        // many rounds, each a fresh player, give it a real chance to show.
        int threads = 8;
        int rounds = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int doubleQueued = 0;

        for (int round = 0; round < rounds; round++) {
            QueueRegistry fresh = new QueueRegistry();
            UUID player = UUID.randomUUID();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger admitted = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    if (fresh.tryQueue(UUID.randomUUID(), List.of(player, UUID.randomUUID()))) {
                        admitted.incrementAndGet();
                    }
                    done.countDown();
                    return null;
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "Every join in round " + round + " finished");
            if (admitted.get() != 1) doubleQueued++;
        }
        pool.shutdown();

        assertEquals(0, doubleQueued, "In every round one join wins and every other one is refused");
    }
}
