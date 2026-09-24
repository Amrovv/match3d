package com.match3d.matchmaking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.match3d.common.MatchEnded;
import com.match3d.core.Lobby;
import com.match3d.core.Player;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** Results called directly, over Postgres. */
class ResultControllerTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired private MatchResults results;
    @Autowired private MatchHistory history;
    @Autowired private RatingStore ratings;
    @Autowired private MatchRepository matches;

    private final FakePublisher publisher = new FakePublisher();

    private ResultController controller() {
        return new ResultController(results, publisher);
    }

    private HttpStatus status(UUID matchId, String winner) {
        ResultRequest request = winner == null ? null : new ResultRequest(winner);
        return HttpStatus.valueOf(controller().result(matchId, request).getStatusCode().value());
    }

    private int rating(Player p) {
        return ratings.ratingOf(p.id()).orElseThrow();
    }

    private Player player(int rating) {
        UUID id = UUID.randomUUID();
        ratings.create(id);
        ratings.set(id, rating);
        return new Player(id, rating, NOW);
    }

    private UUID match(Lobby lobby) {
        UUID id = UUID.randomUUID();
        history.record(id, NOW, lobby);
        return id;
    }

    @Test void testGivenWinnerMovesRatings() {
        Player a1 = player(2500), a2 = player(2500), b1 = player(2500), b2 = player(2500);
        UUID id = match(new Lobby(List.of(a1, a2), List.of(b1, b2)));

        var response = controller().result(id, new ResultRequest("A"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new ResultResponse(id, "A"), response.getBody());
        assertEquals(2600, rating(a1));
        assertEquals(2600, rating(a2));
        assertEquals(2400, rating(b1));
        assertEquals(2400, rating(b2));
        MatchRow row = matches.findById(id).orElseThrow();
        assertEquals('A', row.getWinner());
        assertNotNull(row.getEndedAt());
        assertEquals(List.of(new MatchEnded(id)), publisher.published, "Intake is told once");
    }

    @Test void testNoBodyTossesCoin() {
        Player a = player(2500), b = player(2500);
        UUID id = match(new Lobby(List.of(a), List.of(b)));

        var body = (ResultResponse) controller().result(id, null).getBody();

        assertTrue(body.winner().equals("A") || body.winner().equals("B"));
        Player won = body.winner().equals("A") ? a : b;
        Player lost = won == a ? b : a;
        assertEquals(2600, rating(won), "The toss decides who gains");
        assertEquals(2400, rating(lost));
    }

    @Test void testSecondResultConflictsAndChangesNothing() {
        Player a = player(2500), b = player(2500);
        UUID id = match(new Lobby(List.of(a), List.of(b)));
        status(id, "A");

        assertEquals(HttpStatus.CONFLICT, status(id, "B"));
        assertEquals(2600, rating(a), "Ratings moved once");
        assertEquals('A', matches.findById(id).orElseThrow().getWinner(), "The first result stands");
        assertEquals(1, publisher.published.size(), "Intake is not told twice");
    }

    @Test void testUnknownMatchNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, status(UUID.randomUUID(), "A"));
        assertTrue(publisher.published.isEmpty());
    }

    @Test void testBadWinnerRejectedBeforeAnyChange() {
        Player a = player(2500), b = player(2500);
        UUID id = match(new Lobby(List.of(a), List.of(b)));

        assertEquals(HttpStatus.BAD_REQUEST, status(id, "a"));
        assertNull(matches.findById(id).orElseThrow().getWinner(), "The match is still open");
        assertEquals(2500, rating(a));
    }

    @Test void testRatingsHeldToScale() {
        Player top = player(4950), bottom = player(50);
        UUID id = match(new Lobby(List.of(top), List.of(bottom)));
        status(id, "A");

        assertEquals(5000, rating(top), "Capped at 5000");
        assertEquals(1, rating(bottom), "Floored at 1");
    }

    @Test void testFailedPublishStillEnds() {
        Player a = player(2500), b = player(2500);
        UUID id = match(new Lobby(List.of(a), List.of(b)));
        EventPublisher broken = event -> { throw new IllegalStateException("broker down"); };

        var response = new ResultController(results, broken).result(id, new ResultRequest("A"));

        assertEquals(HttpStatus.OK, response.getStatusCode(), "The result stands without the event");
        assertEquals(2600, rating(a));
    }

    /** Outside the test transaction, so each thread commits on its own connection. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testConcurrentResultsOneWins() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 20; round++) {
                Player a = player(2500), b = player(2500);
                UUID id = match(new Lobby(List.of(a), List.of(b)));
                CountDownLatch start = new CountDownLatch(1);
                List<Future<HttpStatus>> calls = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    String winner = t % 2 == 0 ? "A" : "B";
                    Callable<HttpStatus> call = () -> {
                        start.await();
                        return status(id, winner);
                    };
                    calls.add(pool.submit(call));
                }
                start.countDown();

                int ok = 0;
                for (Future<HttpStatus> c : calls) if (c.get() == HttpStatus.OK) ok++;
                assertEquals(1, ok, "One result wins, round " + round);
                assertEquals(5000, rating(a) + rating(b), "Ratings moved exactly once, round " + round);
                assertEquals(2600, Math.max(rating(a), rating(b)), "By one step, round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** One player in two matches, both ended together: neither change is lost. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testConcurrentResultsForOnePlayerBothCount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                Player shared = player(2500);
                UUID first = match(new Lobby(List.of(shared), List.of(player(2500))));
                UUID second = match(new Lobby(List.of(shared), List.of(player(2500))));
                CountDownLatch start = new CountDownLatch(1);
                Future<HttpStatus> one = pool.submit(() -> { start.await(); return status(first, "A"); });
                Future<HttpStatus> two = pool.submit(() -> { start.await(); return status(second, "A"); });
                start.countDown();
                one.get();
                two.get();

                assertEquals(2700, rating(shared), "Two wins, both counted, round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
