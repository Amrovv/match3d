package com.match3d.common;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Every event survives the trip to JSON and back, and a broken message fails loudly. */
class EventJsonTest {

    private static final Instant AT = Instant.parse("2026-09-22T14:03:11.412Z");

    /** Writes the event and reads it back as the same type. */
    private static <T> T roundTrip(T event, Class<T> type) {
        return EventJson.fromBytes(EventJson.toBytes(event), type);
    }

    @Test void testEntryQueuedSurvivesTheTrip() {
        EntryQueued solo = new EntryQueued(UUID.randomUUID(), List.of(UUID.randomUUID()), AT);

        assertEquals(solo, roundTrip(solo, EntryQueued.class), "Every field, the instant included, comes back");
    }

    @Test void testEntryLeftSurvivesTheTrip() {
        EntryLeft left = new EntryLeft(UUID.randomUUID());

        assertEquals(left, roundTrip(left, EntryLeft.class), "The id comes back");
    }

    @Test void testEntryRejectedSurvivesTheTrip() {
        EntryRejected rejected = new EntryRejected(UUID.randomUUID(), EntryRejected.Reason.SPREAD_TOO_WIDE);

        assertEquals(rejected, roundTrip(rejected, EntryRejected.class), "The reason comes back as the same enum value");
    }

    @Test void testEntryMatchedSurvivesTheTrip() {
        EntryMatched matched = new EntryMatched(UUID.randomUUID(),
                List.of(UUID.randomUUID(), UUID.randomUUID()), List.of(UUID.randomUUID()));

        assertEquals(matched, roundTrip(matched, EntryMatched.class), "Both teams come back, in order");
    }

    @Test void testMatchEndedSurvivesTheTrip() {
        MatchEnded ended = new MatchEnded(UUID.randomUUID());

        assertEquals(ended, roundTrip(ended, MatchEnded.class), "The id comes back");
    }

    @Test void testMatchmakingStartedSurvivesTheTrip() {
        MatchmakingStarted started = new MatchmakingStarted(AT);

        assertEquals(started, roundTrip(started, MatchmakingStarted.class), "The start time comes back");
    }

    @Test void testEntryAcceptedSurvivesTheTrip() {
        EntryAccepted accepted = new EntryAccepted(UUID.randomUUID(), 2537);

        assertEquals(accepted, roundTrip(accepted, EntryAccepted.class), "The rating comes back");
    }

    @Test void testMatchmakingAliveSurvivesTheTrip() {
        MatchmakingAlive alive = new MatchmakingAlive(AT, List.of(new WaitBand(25, 90.5, 3), new WaitBand(26, 40, 2)));
        MatchmakingAlive quiet = new MatchmakingAlive(AT, List.of());

        assertEquals(alive, roundTrip(alive, MatchmakingAlive.class), "Every band comes back");
        assertEquals(quiet, roundTrip(quiet, MatchmakingAlive.class), "And an empty hour");
    }

    @Test void testRatingsFallInBandsOfAHundred() {
        assertEquals(25, WaitBand.of(2500));
        assertEquals(25, WaitBand.of(2599));
        assertEquals(26, WaitBand.of(2600));
        assertEquals(50, WaitBand.of(5000));
    }

    @Test void testTheInstantIsWrittenAsReadableText() {
        String json = new String(EventJson.toBytes(new EntryLeft(UUID.randomUUID())), StandardCharsets.UTF_8);
        String queued = new String(EventJson.toBytes(
                new EntryQueued(UUID.randomUUID(), List.of(), AT)), StandardCharsets.UTF_8);

        assertTrue(json.startsWith("{\"entryId\""), "Records are written as plain JSON objects: " + json);
        assertTrue(queued.contains("\"2026-09-22T14:03:11.412Z\""),
                "A queue time must read as a date in the dashboard, not as a bare number: " + queued);
    }

    @Test void testBrokenBytesFailLoudlyNeg() {
        byte[] broken = "{not json".getBytes(StandardCharsets.UTF_8);

        assertThrows(UncheckedIOException.class, () -> EventJson.fromBytes(broken, EntryLeft.class),
                "A corrupt message must fail, not come back as an empty event");
    }
}
