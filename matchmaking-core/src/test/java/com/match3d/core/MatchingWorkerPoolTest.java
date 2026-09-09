package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchingWorkerPoolTest {

    private SkillIndex index;
    private FairnessHeap heap;
    private MatchMaker matcher;

    @BeforeEach
    void setUp() {
        index = new SkillIndex();
        heap = new FairnessHeap();
        matcher = new MatchMaker(index, heap);
    }

    /**
     * A player queued waitedFor seconds ago, joined to both structures at once.
     *
     * Queue times run from the real clock rather than a fixed instant, because
     * the workers read Instant.now on every pass.
     */
    private Player join(int rating, int waitedFor) {
        Player player = new Player(UUID.randomUUID(), rating, Instant.now().minusSeconds(waitedFor));
        index.insert(player);
        heap.insert(player);
        return player;
    }

    /** count players at one rating, the first of them the longest waiting. */
    private List<Player> joinCluster(int rating, int count) {
        List<Player> joined = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            joined.add(join(rating, count - i));
        }
        return joined;
    }

    // starting and stopping

    @Test void testPoolStartsAndStopsCleanly() throws InterruptedException {
        joinCluster(1500, 200);
        MatchingWorkerPool pool = new MatchingWorkerPool(matcher, 4);

        pool.start();
        Thread.sleep(50);
        MatchingWorkerPool.Run run = assertDoesNotThrow(pool::stop,
                "stop returns once every worker has finished, or throws if one did not");

        assertNotNull(run.lobbies(), "A completed run always has a lobby list, even an empty one");
        assertNotNull(run.failures(), "A completed run always has a failure list, even an empty one");
        assertFalse(run.lobbies().isEmpty(),
                "Two hundred clustered players and fifty milliseconds is ample for a lobby to form");
    }
}
