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
