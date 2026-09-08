package com.match3d.core;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OverlapTest {

    private static final Instant BASE = Instant.parse("2026-09-07T00:00:00Z");

    /** A player at the given rating. Wait time is irrelevant here, radius arrives as a parameter. */
    private static Player rated(int rating) {
        return new Player(UUID.randomUUID(), rating, BASE);
    }

    // seeding

    @Test void testSeedSpansOnlyTheOnePlayer() {
        Overlap seeded = Overlap.of(rated(1000), 50);

        assertEquals(1000, seeded.minR(), "One player spans their own rating and nothing wider");
        assertEquals(1000, seeded.maxR(), "One player spans their own rating and nothing wider");
        assertEquals(950, seeded.floor(), "The reach below is the rating less the radius");
        assertEquals(1050, seeded.ceiling(), "The reach above is the rating plus the radius");
    }

    @Test void testSeedIsValidPos() {
        assertTrue(Overlap.of(rated(1000), 0).valid(),
                "A set of one is valid even at zero radius, since a player always reaches themselves");
    }

    // extending

    @Test void testExtendWidensTheSpan() {
        Overlap extended = Overlap.of(rated(1000), 500).extendedBy(rated(1200), 500);

        assertEquals(1000, extended.minR(), "The lower rating becomes the bottom of the span");
        assertEquals(1200, extended.maxR(), "The higher rating becomes the top of the span");
    }

    @Test void testExtendNarrowsTheReach() {
        // The candidate reaches from 1100 to 1300, the seed from 500 to 1500.
        // The surviving reach is the tighter end in each direction.
        Overlap extended = Overlap.of(rated(1000), 500).extendedBy(rated(1200), 100);

        assertEquals(1100, extended.floor(), "The least generous lower reach wins, so floor is a maximum");
        assertEquals(1300, extended.ceiling(), "The least generous upper reach wins, so ceiling is a minimum");
    }

    @Test void testExtendDoesNotTouchTheOriginal() {
        Overlap seeded = Overlap.of(rated(1000), 50);
        seeded.extendedBy(rated(4000), 50);

        assertEquals(1000, seeded.minR(), "Extending returns a new value, it does not mutate");
        assertEquals(1000, seeded.maxR(), "Extending returns a new value, it does not mutate");
        assertEquals(950, seeded.floor(), "A rejected candidate must leave nothing to undo");
        assertEquals(1050, seeded.ceiling(), "A rejected candidate must leave nothing to undo");
    }

    // acceptance

    @Test void testMutuallyReachingPairPos() {
        Overlap pair = Overlap.of(rated(1000), 200).extendedBy(rated(1100), 200);

        assertTrue(pair.valid(), "Each reaches the other, so the pair is a valid set");
    }

    @Test void testCandidateCannotReachTheAnchorNeg() {
        // The anchor's window covers the candidate, but not the other way round.
        // Consent has to be mutual, or a long waiter conscripts someone who
        // has only just queued.
        Overlap pair = Overlap.of(rated(1000), 2000).extendedBy(rated(2000), 50);

        assertFalse(pair.valid(), "A one sided window is not agreement, so the pair is rejected");
    }

    @Test void testAnchorCannotReachTheCandidateNeg() {
        Overlap pair = Overlap.of(rated(1000), 50).extendedBy(rated(2000), 2000);

        assertFalse(pair.valid(), "The same rejection holds whichever side is the narrow one");
    }

    // boundaries

    @Test void testReachExactlyMeetsTheSpanPos() {
        Overlap pair = Overlap.of(rated(1000), 100).extendedBy(rated(1100), 100);

        assertEquals(1000, pair.floor(), "Fixture check: the reach lands exactly on the span");
        assertEquals(1100, pair.ceiling(), "Fixture check: the reach lands exactly on the span");
        assertTrue(pair.valid(), "Reaching exactly as far as the span is enough, the bound is inclusive");
    }

    @Test void testReachOneShortOfTheSpanNeg() {
        Overlap pair = Overlap.of(rated(1000), 99).extendedBy(rated(1100), 100);

        assertFalse(pair.valid(), "One rating point short of the span is still short");
    }

    // the clique condition

    @Test void testMembersMustReachEachOtherNotOnlyTheAnchorNeg() {
        // Both candidates sit inside the anchor's window, and the anchor sits
        // inside each of theirs, so a star shaped check would accept both. They
        // are 800 apart with a reach of 500, so they do not accept each other.
        Overlap anchorOnly = Overlap.of(rated(1000), 500);

        assertTrue(anchorOnly.extendedBy(rated(1400), 500).valid(),
                "Fixture check: the first candidate is acceptable on their own");

        Overlap both = anchorOnly.extendedBy(rated(1400), 500).extendedBy(rated(600), 500);

        assertFalse(both.valid(), "Every pair must agree, not merely every player with the anchor");
    }

    @Test void testAnInvalidSetCannotBeRepairedByAdding() {
        Overlap broken = Overlap.of(rated(1000), 50).extendedBy(rated(2000), 50);
        assertFalse(broken.valid(), "Fixture check: the set starts out invalid");

        for (int rating = 900; rating <= 2100; rating += 100) {
            assertFalse(broken.extendedBy(rated(rating), 5000).valid(),
                    "Adding can only widen the span and narrow the reach, at rating " + rating);
        }
    }

    @Test void testWideningOneMemberDoesNotRescueANarrowOne() {
        Overlap generous = Overlap.of(rated(1000), 5000).extendedBy(rated(1500), 5000);
        assertTrue(generous.valid(), "Fixture check: two generous players accept each other");

        assertFalse(generous.extendedBy(rated(1200), 10).valid(),
                "One narrow member is enough to break a set, however generous the rest are");
    }
}
