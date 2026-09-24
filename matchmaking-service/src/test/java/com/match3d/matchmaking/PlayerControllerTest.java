package com.match3d.matchmaking;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** Registration called directly, over Postgres. */
class PlayerControllerTest extends PostgresTest {

    @Autowired private RatingStore ratings;

    private PlayerController controller() {
        return new PlayerController(ratings);
    }

    private HttpStatus create(UUID id) {
        return HttpStatus.valueOf(controller().create(new CreatePlayerRequest(id)).getStatusCode().value());
    }

    @Test void testCreatedAtStartingRating() {
        UUID id = UUID.randomUUID();

        assertEquals(HttpStatus.CREATED, create(id));
        assertEquals(RatingStore.STARTING_RATING, ratings.ratingOf(id).orElseThrow());
    }

    @Test void testExistingConflictsAndKeepsRating() {
        UUID id = UUID.randomUUID();
        create(id);
        rate(id, 3100);

        assertEquals(HttpStatus.CONFLICT, create(id));
        assertEquals(3100, ratings.ratingOf(id).orElseThrow(), "A second create must not reset the rating");
    }

    @Test void testMissingIdBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, create(null));
    }

    /** Outside the test transaction, so each thread commits on its own connection. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testConcurrentCreatesOneWins() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 50; round++) {
                UUID id = UUID.randomUUID();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<HttpStatus>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    Callable<HttpStatus> call = () -> {
                        start.await();
                        return create(id);
                    };
                    results.add(pool.submit(call));
                }
                start.countDown();

                int created = 0;
                for (Future<HttpStatus> r : results) if (r.get() == HttpStatus.CREATED) created++;
                assertEquals(1, created, "Exactly one of " + threads + " creates wins, round " + round);
            }
        } finally {
            pool.shutdownNow();
            wipe();
        }
    }
}
