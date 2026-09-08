package com.match3d.core;

import java.time.Duration;


/**
 * How wide a rating window a player accepts, given how long they have waited.
 * A saturating exponential, W(t) = MAX - (MAX - BASE) * e^(-K * t)
 * Growth fastest at t = 0 and MAX is an asymptote.
 *
 * The value is a radius, not a width. The window it describes is
 * [rating - r, rating + r].
 *
 * Pure and stateless. Wait time is a parameter, never read from a clock here,
 * so the curve can be tested at any point in time.
 */
public final class WideningFunction {

    /** Radius at zero wait. The tightest lobby the engine will form. */
    static final int BASE_RADIUS = 50;

    /**
     * The maximum radius.
     * At 2500 every player's window covers the middle of the domain,
     * where most of the distribution sits.
     */
    static final int MAX_RADIUS = 2500;

    /**
     * Decay constant, per second.
     * Design choice: At 60 seconds, the window covers 10% of the domain (r=250)
     */
    static final double K = 0.00142;

    private WideningFunction() {
    }

    /**
     * The rating radius a player accepts after waiting the given time.
     *
     * Rounds down, so a candidate exactly on the boundary is excluded.
     * Throws IllegalArgumentException if waited is negative.
     */
    public static int ratingRadius(Duration waited) {
        if (waited.isNegative()) {
            throw new IllegalArgumentException("waited must be non-negative");
        }

        double seconds = waited.toMillis() / 1000.0;
        return (int) Math.floor(MAX_RADIUS - (MAX_RADIUS - BASE_RADIUS) * Math.exp(-K * seconds));
    }
}
