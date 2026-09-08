package com.match3d.core;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTest {

    private static final Instant BASE = Instant.parse("2026-09-07T00:00:00Z");

    // construction

    @Test void testNullIdNeg() {
        assertThrows(NullPointerException.class, () -> new Player(null, 1000, BASE),
                "A player without an id has no identity at all");
    }

    @Test void testNullQueuedAtNeg() {
        assertThrows(NullPointerException.class, () -> new Player(UUID.randomUUID(), 1000, null),
                "Wait time drives fairness, so a missing queue time must fail loudly");
    }

    @Test void testComponentsAreReadBack() {
        UUID id = UUID.randomUUID();
        Player p = new Player(id, 1000, BASE);

        assertEquals(id, p.id(), "The id is stored as given");
        assertEquals(1000, p.rating(), "The rating is stored as given");
        assertEquals(BASE, p.queuedAt(), "The queue time is stored as given");
    }

    // equality

    @Test void testEqualsIgnoresRatingAndQueuedAtPos() {
        UUID id = UUID.randomUUID();
        Player queued = new Player(id, 1000, BASE);
        Player rebuilt = new Player(id, 2000, BASE.plusSeconds(600));

        assertEquals(queued, rebuilt,
                "Identity is the id alone, so a player rebuilt from a message equals the one inserted");
    }

    @Test void testEqualsDifferentIdNeg() {
        Player one = new Player(UUID.randomUUID(), 1000, BASE);
        Player other = new Player(UUID.randomUUID(), 1000, BASE);

        assertNotEquals(one, other, "Two different people sharing a rating and a queue time are not equal");
    }

    @Test void testEqualsSelfPos() {
        Player p = new Player(UUID.randomUUID(), 1000, BASE);

        assertEquals(p, p, "Equality must be reflexive");
    }

    @Test void testEqualsNullNeg() {
        Player p = new Player(UUID.randomUUID(), 1000, BASE);

        assertNotEquals(null, p, "A player is never equal to null");
    }

    @Test void testEqualsOtherTypeNeg() {
        Player p = new Player(UUID.randomUUID(), 1000, BASE);

        assertNotEquals("not a player", p, "A player is never equal to an unrelated type");
    }

    @Test void testHashCodeAgreesWithEquals() {
        UUID id = UUID.randomUUID();
        Player queued = new Player(id, 1000, BASE);
        Player rebuilt = new Player(id, 2000, BASE.plusSeconds(600));

        assertEquals(queued.hashCode(), rebuilt.hashCode(),
                "Equal players must hash alike, or a hash based bucket will not find them");
    }

    // the contract SkillIndex depends on

    @Test void testSetTreatsSameIdAsOnePlayer() {
        UUID id = UUID.randomUUID();
        Set<Player> bucket = new LinkedHashSet<>();
        bucket.add(new Player(id, 1000, BASE));

        assertFalse(bucket.add(new Player(id, 1000, BASE.plusSeconds(600))),
                "A bucket rejects a second entry for the same id even with a later queue time");
        assertTrue(bucket.remove(new Player(id, 1000, BASE.plusSeconds(9999))),
                "A bucket removes by id, without needing the exact object that was inserted");
    }
}
