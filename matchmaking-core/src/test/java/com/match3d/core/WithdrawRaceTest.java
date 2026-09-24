package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Leaves and rejoins racing passes. A withdraw that returned true must keep
 * that entry out of every lobby, including an anchor mid walk. A rejoin must
 * leave the entry in exactly one place, a lobby or the queue.
 */
class WithdrawRaceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    private static final int PLAYERS = 60;
    private static final int RUNNERS = 4;
    private static final int ROUNDS = 300;

    @Test void testLeavesAndRejoinsMidPass() throws InterruptedException {
        for (int r = 0; r < ROUNDS; r++) {
            SkillIndex index = new SkillIndex();
            MatchMaker matcher = new MatchMaker(index, new FairnessHeap());
            Map<UUID, Player> byId = new HashMap<>();
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < PLAYERS; i++) {
                Player p = new Player(UUID.randomUUID(), 1500, NOW.minusSeconds(PLAYERS - i));
                matcher.enqueue(p);
                ids.add(p.id());
                byId.put(p.id(), p);
            }
            Collections.shuffle(ids);

            Set<UUID> withdrawn = Collections.synchronizedSet(new HashSet<>());
            Set<UUID> rejoined = Collections.synchronizedSet(new HashSet<>());
            Set<UUID> refused = Collections.synchronizedSet(new HashSet<>());
            ConcurrentLinkedQueue<Lobby> lobbies = new ConcurrentLinkedQueue<>();
            AtomicBoolean leaving = new AtomicBoolean(true);
            CountDownLatch start = new CountDownLatch(1);

            List<Thread> threads = new ArrayList<>();
            for (int w = 0; w < RUNNERS; w++) {
                threads.add(new Thread(() -> {
                    await(start);
                    while (leaving.get()) matcher.formLobby(NOW).ifPresent(lobbies::add);
                }));
            }
            Thread leaver = new Thread(() -> {
                await(start);
                for (int i = 0; i < ids.size(); i += 2) {
                    UUID id = ids.get(i);
                    if (!matcher.withdraw(id)) continue;
                    if (i % 4 != 0) withdrawn.add(id);
                    else if (matcher.enqueue(byId.get(id))) rejoined.add(id);
                    else refused.add(id);
                }
                leaving.set(false);
            });
            threads.add(leaver);

            threads.forEach(Thread::start);
            start.countDown();
            for (Thread t : threads) t.join();

            assertTrue(refused.isEmpty(), "Round " + r + ": a rejoin straight after a withdraw was refused");

            Map<UUID, Integer> placed = new HashMap<>();
            for (Lobby lobby : lobbies) {
                for (Player p : lobby.members()) {
                    assertFalse(withdrawn.contains(p.id()),
                            "Round " + r + ": " + p.id() + " was withdrawn, then matched");
                    placed.merge(p.id(), 1, Integer::sum);
                }
            }
            for (UUID id : rejoined) {
                int places = placed.getOrDefault(id, 0) + (index.contains(id) ? 1 : 0);
                assertEquals(1, places, "Round " + r + ": " + id + " rejoined, then was lost or doubled");
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
