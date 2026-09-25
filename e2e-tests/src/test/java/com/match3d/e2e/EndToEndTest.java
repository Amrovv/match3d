package com.match3d.e2e;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole system from outside: register, queue, match, result. The first
 * phase records what it made; the after restart phase checks it survived.
 */
class EndToEndTest {

    private static final String INTAKE = System.getProperty("e2e.intake", "http://localhost:8080");
    private static final String MATCHMAKING = System.getProperty("e2e.matchmaking", "http://localhost:8081");
    private static final Path RECORD = Path.of(System.getProperty("e2e.record", "build/e2e/last-run.properties"));

    private static final int STARTING_RATING = 2500;
    private static final int CHANGE = 100;
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private final Http http = new Http();

    @Test
    @EnabledIfSystemProperty(named = "e2e.phase", matches = "flow")
    void testRegisterQueueMatchAndResult() throws Exception {
        List<UUID> players = Stream.generate(UUID::randomUUID).limit(10).toList();
        for (UUID p : players) {
            assertEquals(201, http.post(MATCHMAKING + "/players", Map.of("id", p)).status(), "register " + p);
            assertEquals(STARTING_RATING, rating(p));
        }

        // A party of two and eight solos, one lobby's worth.
        assertEquals(202, http.post(INTAKE + "/queue/join", Map.of("memberIds", players.subList(0, 2))).status());
        for (UUID p : players.subList(2, 10)) {
            assertEquals(202, http.post(INTAKE + "/queue/join", Map.of("memberIds", List.of(p))).status());
        }

        // Players left queued by an earlier failed run may share a lobby with these, so each match is taken as found.
        Map<UUID, JsonNode> matchOf = new HashMap<>();
        for (UUID p : players) {
            JsonNode status = waitFor(() -> status(p), s -> s.path("state").asText().equals("MATCHED"), "matched " + p);
            matchOf.put(p, status.get("match"));
        }

        Set<UUID> ended = new HashSet<>();
        for (JsonNode match : matchOf.values()) {
            UUID matchId = UUID.fromString(match.get("matchId").asText());
            if (!ended.add(matchId)) continue;

            JsonNode recorded = http.get(MATCHMAKING + "/matches/" + matchId).body();
            assertEquals(ids(match.get("teamA")), ids(recorded.get("teamA")), "both services agree on team A");
            assertEquals(ids(match.get("teamB")), ids(recorded.get("teamB")), "both services agree on team B");

            assertEquals(200, result(matchId), "result for " + matchId);
            assertEquals(409, result(matchId), "a second result for " + matchId);
        }

        Properties record = new Properties();
        for (UUID p : players) {
            JsonNode match = matchOf.get(p);
            UUID matchId = UUID.fromString(match.get("matchId").asText());
            int expected = STARTING_RATING + (ids(match.get("teamA")).contains(p) ? CHANGE : -CHANGE);

            waitFor(() -> status(p), s -> s.path("state").asText().equals("NOT_QUEUED"), "released " + p);
            assertEquals(expected, rating(p), "rating of " + p);
            assertTrue(historyOf(p).contains(matchId), "history of " + p);

            record.setProperty(p + ".rating", String.valueOf(expected));
            record.setProperty(p + ".match", matchId.toString());
        }

        Files.createDirectories(RECORD.getParent());
        try (Writer out = Files.newBufferedWriter(RECORD)) {
            record.store(out, "players and matches made by the last flow run");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "e2e.phase", matches = "after-restart")
    void testRunSurvivesRestart() throws Exception {
        assertTrue(Files.exists(RECORD), "no record at " + RECORD + ", run the flow phase first");
        Properties record = new Properties();
        try (Reader in = Files.newBufferedReader(RECORD)) {
            record.load(in);
        }

        Set<UUID> matches = new HashSet<>();
        for (String key : record.stringPropertyNames()) {
            if (!key.endsWith(".rating")) continue;
            UUID p = UUID.fromString(key.substring(0, key.indexOf('.')));
            UUID matchId = UUID.fromString(record.getProperty(p + ".match"));
            matches.add(matchId);

            assertEquals(Integer.parseInt(record.getProperty(key)), rating(p), "rating of " + p);
            assertTrue(historyOf(p).contains(matchId), "history of " + p);
            assertEquals("NOT_QUEUED", status(p).path("state").asText(), "status of " + p);
        }
        assertFalse(matches.isEmpty(), "the record names no players");

        for (UUID matchId : matches) {
            assertEquals(200, http.get(MATCHMAKING + "/matches/" + matchId).status(), "match " + matchId);
            assertEquals(409, result(matchId), "the result of " + matchId + " was kept");
        }
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

    /** Polls until the reply passes, since matching and release arrive over the queue after the request returns. */
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
