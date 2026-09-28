package com.match3d.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A crashed intake copy costs no player. A full flow runs while the wrapper
 * script deletes an intake pod under it, once this test signals through a
 * marker file that its load is under way. An abrupt death is the hard case: a
 * request may meet the dying pod and fail, so every call retries and succeeds
 * on a second try, and no player is lost or duplicated. A planned maintenance
 * shutdown is gentler than this, so it is covered by the same proof.
 */
class DisruptionTest {

    private static final String INTAKE = System.getProperty("e2e.intake", "http://localhost:8080");
    private static final String MATCHMAKING = System.getProperty("e2e.matchmaking", "http://localhost:8081");
    private static final Path STARTED = Path.of(System.getProperty("e2e.started", "build/e2e/started"));

    private static final int STARTING_RATING = 2500;
    private static final int CHANGE = 100;
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private final Http http = new Http();

    @Test
    @EnabledIfSystemProperty(named = "e2e.phase", matches = "crash")
    void aCrashedCopyCostsNoPlayer() throws Exception {
        List<UUID> players = Stream.generate(UUID::randomUUID).limit(10).toList();
        for (UUID p : players) {
            assertEquals(201, retry(() -> http.post(MATCHMAKING + "/players", Map.of("id", p))).status(), "register " + p);
        }

        signalStarted();

        assertEquals(202, retry(() -> http.post(INTAKE + "/queue/join", Map.of("memberIds", players.subList(0, 2)))).status());
        for (UUID p : players.subList(2, 10)) {
            assertEquals(202, retry(() -> http.post(INTAKE + "/queue/join", Map.of("memberIds", List.of(p)))).status(), "join " + p);
        }

        Map<UUID, JsonNode> matchOf = new HashMap<>();
        for (UUID p : players) {
            JsonNode matched = waitFor(() -> status(p), s -> s.path("state").asText().equals("MATCHED"), "matched " + p);
            matchOf.put(p, matched.get("match"));
        }

        Set<UUID> ended = new HashSet<>();
        for (JsonNode match : matchOf.values()) {
            UUID matchId = UUID.fromString(match.get("matchId").asText());
            if (!ended.add(matchId)) continue;
            int recorded = result(matchId);
            assertTrue(recorded == 200 || recorded == 409, "result for " + matchId + " was " + recorded);
        }

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

    /** Tells the wrapper the load is under way, so it can delete a pod under it. */
    private void signalStarted() throws Exception {
        Files.createDirectories(STARTED.getParent());
        Files.write(STARTED, new byte[0]);
    }

    private interface Send {
        Http.Reply call() throws Exception;
    }

    /** Up to three tries, for a request that may have met a pod as it died. */
    private static Http.Reply retry(Send send) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Http.Reply reply = send.call();
                if (reply.status() < 500) return reply;
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(500);
        }
        if (last != null) throw last;
        return send.call();
    }

    private int rating(UUID player) throws Exception {
        Http.Reply reply = retry(() -> http.get(MATCHMAKING + "/players/" + player));
        assertEquals(200, reply.status(), "player " + player);
        return reply.body().get("rating").asInt();
    }

    private JsonNode status(UUID player) throws Exception {
        return retry(() -> http.get(INTAKE + "/queue/status/" + player)).body();
    }

    private int result(UUID matchId) throws Exception {
        return retry(() -> http.post(MATCHMAKING + "/matches/" + matchId + "/result", Map.of("winner", "A"))).status();
    }

    private Set<UUID> historyOf(UUID player) throws Exception {
        Set<UUID> matchIds = new HashSet<>();
        for (JsonNode match : retry(() -> http.get(MATCHMAKING + "/players/" + player + "/history")).body()) {
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
