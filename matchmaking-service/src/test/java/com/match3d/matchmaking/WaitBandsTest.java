package com.match3d.matchmaking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.match3d.common.WaitBand;
import com.match3d.core.Lobby;
import com.match3d.core.Player;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** The heartbeat's waits by rating band, over seeded matches: grouping, the hour window, and an empty hour. */
class WaitBandsTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired private MatchHistory history;
    @Autowired private RatingStore ratings;

    /** A player at this rating who queued this many seconds before the match formed. */
    private Player waited(int rating, long seconds, Instant formedAt) {
        UUID id = UUID.randomUUID();
        ratings.create(id);
        return new Player(id, rating, formedAt.minusSeconds(seconds));
    }

    /** A one a side match: one player at each rating, each waiting the given seconds. */
    private void matched(int ratingA, int ratingB, long seconds, Instant formedAt) {
        history.record(UUID.randomUUID(), formedAt, new Lobby(
                List.of(waited(ratingA, seconds, formedAt)), List.of(waited(ratingB, seconds, formedAt))));
    }

    @Test void testGroupsPlayersIntoBandsOfAHundred() {
        matched(2500, 2599, 20, NOW.minusSeconds(60));
        matched(2550, 2600, 40, NOW.minusSeconds(120));

        assertEquals(List.of(new WaitBand(25, 80, 3), new WaitBand(26, 40, 1)), history.waitBands(NOW),
                "2500, 2599 and 2550 share band 25; 2600 starts band 26");
    }

    @Test void testLeavesOutMatchesOlderThanAnHour() {
        matched(2500, 2500, 20, NOW.minusSeconds(60));
        matched(2500, 2500, 900, NOW.minusSeconds(3601));

        assertEquals(List.of(new WaitBand(25, 40, 2)), history.waitBands(NOW), "Only the recent match counts");
    }

    @Test void testEmptyHourHasNoBands() {
        matched(2500, 2500, 20, NOW.minusSeconds(3601));

        assertEquals(List.of(), history.waitBands(NOW));
    }
}
