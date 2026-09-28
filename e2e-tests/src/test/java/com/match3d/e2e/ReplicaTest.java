package com.match3d.e2e;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Intake as several copies behind one Service. The wrapper script scales
 * intake to a known number of copies and runs this against each: it proves the
 * Service reaches every live copy, and that a full flow through them loses and
 * duplicates no player, checked from the databases' view through the API.
 *
 * e2e.copies is how many intake copies the wrapper left running for this run.
 */
class ReplicaTest {

    private static final String INTAKE = System.getProperty("e2e.intake", "http://localhost:8080");
    private static final String MATCHMAKING = System.getProperty("e2e.matchmaking", "http://localhost:8081");
    private static final int COPIES = Integer.getInteger("e2e.copies", 3);

    private static final int STARTING_RATING = 2500;
    private static final int CHANGE = 100;
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private final Http http = new Http();

    @Test
    @EnabledIfSystemProperty(named = "e2e.phase", matches = "replicas")
    void theServiceReachesEveryLiveCopy() throws Exception {
        // Probe the one Service address until every live copy has answered,
        // and never let more than that many distinct copies appear.
        Set<String> pods = new HashSet<>();
        Instant deadline = Instant.now().plus(PATIENCE);
        UUID nobody = UUID.fromString("00000000-0000-0000-0000-000000000000");
        while (pods.size() < COPIES) {
            if (Instant.now().isAfter(deadline)) {
                fail("only " + pods.size() + " of " + COPIES + " copies answered: " + pods);
            }
            String pod = http.freshGet(INTAKE + "/queue/status/" + nobody).pod();
            if (!pod.isBlank()) pods.add(pod);
            assertTrue(pods.size() <= COPIES, "more copies answered than are running: " + pods);
            Thread.sleep(100);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "e2e.phase", matches = "replicas")
    void aFlowThroughTheCopiesLosesNoPlayer() throws Exception {
        List<UUID> players = Stream.generate(UUID::randomUUID).limit(10).toList();
        for (UUID p : players) {
            assertEquals(201, http.post(MATCHMAKING + "/players", Map.of("id", p)).status(), "register " + p);
        }

        // Join through the Service, which spreads these across the live copies.
        assertEquals(202, http.post(INTAKE + "/queue/join", Map.of("memberIds", players.subList(0, 2))).status());
        for (UUID p : players.subList(2, 10)) {
            assertEquals(202, http.post(INTAKE + "/queue/join", Map.of("memberIds", List.of(p))).status());
        }

        // Every player reaches a match. Collect them all before recording any
        // result, since one result releases everyone on that match at once.
        Map<UUID, JsonNode> matchOf = new HashMap<>();
        for (UUID p : players) {
            JsonNode matched = waitFor(() -> status(p), s -> s.path("state").asText().equals("MATCHED"), "matched " + p);
            matchOf.put(p, matched.get("match"));
        }

        // Record each distinct match once; a second result is refused.
        Set<UUID> ended = new HashSet<>();
        for (JsonNode match : matchOf.values()) {
            UUID matchId = UUID.fromString(match.get("matchId").asText());
            if (!ended.add(matchId)) continue;
            int recorded = result(matchId);
            assertTrue(recorded == 200 || recorded == 409, "result for " + matchId + " was " + recorded);
        }

        // Each player comes back released with a moved rating and the match in
        // their history: none lost, none duplicated.
        for (UUID p : players) {
            JsonNode match = matchOf.get(p);
            UUID matchId = UUID.fromString(match.get("matchId").asText());
            boolean onTeamA = ids(match.get("teamA")).contains(p);

            waitFor(() -> status(p), s -> s.path("state").asText().equals("NOT_QUEUED"), "released " + p);
            int expected = STARTING_RATING + (onTeamA ? CHANGE : -CHANGE);
            assertEquals(expected, rating(p), "rating of " + p);
            assertTrue(historyOf(p).contains(matchId), "history of " + p);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "e2e.phase", matches = "replicas")
    void onePlayerJoiningThroughEveryCopyAtOnceIsQueuedOnce() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals(201, http.post(MATCHMAKING + "/players", Map.of("id", player)).status(), "register " + player);

        // Fire the same join at once, each on its own connection so the Service
        // can hand them to different copies. Whichever copy each lands on, the
        // shared database refuses a second entry for the player.
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Http.Reply>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return http.freshPost(INTAKE + "/queue/join", Map.of("memberIds", List.of(player)));
            }));
        }
        ready.await();
        go.countDown();

        for (Future<Http.Reply> f : futures) {
            int status = f.get().status();
            // 202 for the entry itself or an idempotent resend of it, 409 for a
            // racing attempt the database turned away. Never a 500: no attempt
            // makes a second entry or errors.
            assertTrue(status == 202 || status == 409, "join returned " + status);
        }
        pool.shutdown();

        // Queued exactly once: the player is queued under the one entry, and a
        // single leave clears them. A second entry would leave them queued still.
        JsonNode queued = status(player);
        assertEquals("QUEUED", queued.path("state").asText(), "queued after the joins");
        assertEquals(player.toString(), queued.path("entryId").asText(), "queued under one entry");
        assertEquals(202, http.post(INTAKE + "/queue/leave", Map.of("entryId", player)).status(), "leave once");
        assertEquals("NOT_QUEUED", status(player).path("state").asText(), "cleared by one leave");
    }

    private int rating(UUID player) throws Exception {
        Http.Reply reply = http.get(MATCHMAKING + "/players/" + player);
        assertEquals(200, reply.status(), "player " + player);
        return reply.body().get("rating").asInt();
    }

    private JsonNode status(UUID player) throws Exception {
        return http.get(INTAKE + "/queue/status/" + player).body();
    }

    private int result(UUID matchId) throws Exception {
        return http.post(MATCHMAKING + "/matches/" + matchId + "/result", Map.of("winner", "A")).status();
    }

    private Set<UUID> historyOf(UUID player) throws Exception {
        Set<UUID> matchIds = new HashSet<>();
        for (JsonNode match : http.get(MATCHMAKING + "/players/" + player + "/history").body()) {
            matchIds.add(UUID.fromString(match.get("matchId").asText()));
        }
        return matchIds;
    }

    private static Set<UUID> ids(JsonNode array) {
        Set<UUID> ids = new HashSet<>();
        for (JsonNode id : array) ids.add(UUID.fromString(id.asText()));
        return ids;
    }

    private interface Call {
        JsonNode get() throws Exception;
    }

    private static JsonNode waitFor(Call call, Predicate<JsonNode> done, String what) throws Exception {
        Instant deadline = Instant.now().plus(PATIENCE);
        JsonNode last = call.get();
        while (!done.test(last)) {
            if (Instant.now().isAfter(deadline)) fail("not " + what + " within " + PATIENCE.toSeconds() + " s, last reply " + last);
            Thread.sleep(250);
            last = call.get();
        }
        return last;
    }
}
