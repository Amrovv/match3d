package com.match3d.core;

import java.time.Duration;


/**
 * How wide a rating window a player accepts, given their wait. A saturating
 * exponential, W(t) = MAX - (MAX - BASE) * e^(-K * t), fastest at t = 0 with
 * MAX as an asymptote.
 *
 * A radius, not a width: the window is [rating - r, rating + r].
 *
 * Pure and stateless, so the curve can be tested at any point on it.
 */
public final class WideningFunction {

    /** Radius at zero wait. The tightest lobby the engine will form. */
    static final int BASE_RADIUS = 50;

    /** At 2500 every window covers the middle, where the distribution sits. */
    static final int MAX_RADIUS = 2500;

    /** Per second. Set so a minute of waiting gives a radius of 250. */
    static final double K = 0.00142;

    private WideningFunction() {
    }

    /**
     * Rounds down, so a candidate exactly on the boundary is excluded. Throws
     * if waited is negative.
     */
    public static int ratingRadius(Duration waited) {
        if (waited.isNegative()) {
            throw new IllegalArgumentException("waited must be non-negative");
        }

        double seconds = waited.toMillis() / 1000.0;
        return (int) Math.floor(MAX_RADIUS - (MAX_RADIUS - BASE_RADIUS) * Math.exp(-K * seconds));
    }
}
