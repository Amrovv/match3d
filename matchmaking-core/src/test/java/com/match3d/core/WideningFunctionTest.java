package com.match3d.core;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WideningFunctionTest {

    /** The radius a player accepts after waiting the given whole seconds. */
    private static int after(long seconds) {
        return WideningFunction.ratingRadius(Duration.ofSeconds(seconds));
    }

    /** W(t) worked out from the constants, so a test can check the code against its own curve. */
    private static double exact(double seconds) {
        return WideningFunction.MAX_RADIUS
                - (WideningFunction.MAX_RADIUS - WideningFunction.BASE_RADIUS)
                * Math.exp(-WideningFunction.K * seconds);
    }

    // base radius

    @Test void testZeroWaitIsBaseRadius() {
        assertEquals(WideningFunction.BASE_RADIUS, WideningFunction.ratingRadius(Duration.ZERO),
                "A player who has not waited accepts exactly the base radius");
    }

    @Test void testNeverNarrowerThanBaseRadius() {
        for (long t = 0; t <= 3600; t += 7) {
            assertTrue(after(t) >= WideningFunction.BASE_RADIUS,
                    "Waiting never tightens the window, at t=" + t);
        }
    }

    // growth

    @Test void testNonDecreasingOverTime() {
        int previous = WideningFunction.ratingRadius(Duration.ZERO);
        for (long t = 1; t <= 3600; t++) {
            int current = after(t);

            assertTrue(current >= previous, "Waiting longer must never shrink the window, at t=" + t
                    + " the radius went " + previous + " to " + current);
            previous = current;
        }
    }

    @Test void testWidensOverAMeaningfulIntervalPos() {
        assertTrue(after(60) > after(0), "A minute of waiting must actually widen the window");
        assertTrue(after(300) > after(60), "Five minutes must be wider than one");
        assertTrue(after(600) > after(300), "Ten minutes must be wider than five");
    }

    @Test void testSubSecondWaitIsNotTruncated() {
        int halfSecond = WideningFunction.ratingRadius(Duration.ofMillis(500));

        assertTrue(halfSecond > WideningFunction.BASE_RADIUS,
                "The curve reads millis, so half a second of waiting still registers");
        assertTrue(halfSecond <= after(1), "Half a second cannot widen further than a whole one");
    }

    // shape

    @Test void testGrowthDecelerates() {
        int firstMinute = after(60) - after(0);
        int secondMinute = after(120) - after(60);
        int tenthMinute = after(600) - after(540);

        assertTrue(secondMinute < firstMinute,
                "Growth is fastest at t=0, so the second minute widens less than the first");
        assertTrue(tenthMinute < secondMinute,
                "Growth keeps slowing, which is what makes this a saturating curve and not a line");
    }

    @Test void testNotLinearNeg() {
        // A straight assertNotEquals is too weak: integer flooring makes two
        // increments of a line differ by one anyway. Comparing very late windows
        // is no better, because a line capped at the maximum flattens there too.
        // So the two windows are wide, adjacent, and early enough that a line of
        // any slope reaching this far is still climbing, and the margin is large.
        int firstFiveMinutes = after(300) - after(0);
        int secondFiveMinutes = after(600) - after(300);

        assertTrue(firstFiveMinutes > 1.3 * secondFiveMinutes,
                "Widening must fall away sharply across equal intervals, which no straight line does:"
                        + " first five minutes " + firstFiveMinutes + ", second " + secondFiveMinutes);
    }

    @Test void testMatchesTheClosedForm() {
        for (long t : new long[] {0, 1, 10, 30, 60, 120, 300, 600, 1800, 3600}) {
            assertEquals((int) Math.floor(exact(t)), after(t),
                    "The radius returned must be W(t), at t=" + t);
        }
    }

    // the asymptote

    @Test void testNeverExceedsMaxRadius() {
        for (long t : new long[] {0, 1, 60, 600, 3600, 86_400, 31_536_000L}) {
            assertTrue(WideningFunction.ratingRadius(Duration.ofSeconds(t)) <= WideningFunction.MAX_RADIUS,
                    "The maximum is an asymptote, so nothing may cross it, at t=" + t);
        }
    }

    @Test void testBelowMaxForAPlausibleQueueTime() {
        assertTrue(after(3600) < WideningFunction.MAX_RADIUS,
                "An hour of waiting must still leave headroom, or the cap is doing the work instead of the curve");
    }

    @Test void testSaturatesAtMaxRadius() {
        assertEquals(WideningFunction.MAX_RADIUS, after(86_400),
                "After a day the exponential term has vanished and the radius sits at the maximum");
    }

    @Test void testExtremeWaitDoesNotOverflow() {
        assertEquals(WideningFunction.MAX_RADIUS,
                WideningFunction.ratingRadius(Duration.ofDays(365 * 100L)),
                "A century of waiting still returns the maximum, not a wrapped int");
    }

    // calibration

    @Test void testSixtySecondsCoversATenthOfTheDomain() {
        // Pins K. Retuning the constant should break this, since the 10% figure
        // is the stated reason the constant has the value it has.
        assertEquals(250, after(60),
                "At 60 seconds the window covers a tenth of the 1 to 5000 rating domain");
    }

    // rounding

    @Test void testRoundsDownNotToNearest() {
        long t = 2;

        assertTrue(exact(t) - Math.floor(exact(t)) > 0.5,
                "Fixture check: t=" + t + " must land above the halfway point, or this proves nothing");
        assertEquals((int) Math.floor(exact(t)), after(t),
                "The radius rounds down, so a candidate on the boundary is excluded rather than let in");
    }

    // purity

    @Test void testSameWaitAlwaysGivesTheSameRadius() {
        Duration waited = Duration.ofSeconds(137);
        int first = WideningFunction.ratingRadius(waited);

        for (int i = 0; i < 100; i++) {
            assertEquals(first, WideningFunction.ratingRadius(waited),
                    "No clock and no state, so repeated calls cannot drift");
        }
    }

    // rejection

    @Test void testNegativeWaitNeg() {
        assertThrows(IllegalArgumentException.class,
                () -> WideningFunction.ratingRadius(Duration.ofSeconds(-1)),
                "A negative wait is a caller bug, not a narrow window");
    }

    @Test void testNegativeSubSecondWaitNeg() {
        assertThrows(IllegalArgumentException.class,
                () -> WideningFunction.ratingRadius(Duration.ofMillis(-1)),
                "A wait a millisecond below zero is still a bug, not a zero wait");
    }

    @Test void testNullWaitNeg() {
        assertThrows(NullPointerException.class, () -> WideningFunction.ratingRadius(null),
                "A missing wait time must fail loudly rather than default to something");
    }
}
