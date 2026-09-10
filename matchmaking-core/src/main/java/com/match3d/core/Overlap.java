package com.match3d.core;

/**
 * Whether every player in a set accepts every other, in four numbers. minR and
 * maxR span the seated ratings; floor and ceiling are the narrowest reach among
 * them. Valid while the reach covers the span.
 *
 * That is the pairwise condition without the pairs, because an interval is
 * contiguous, so a candidate costs O(1) to test.
 *
 * Immutable. A rejected candidate leaves nothing to undo.
 */
record Overlap(int minR, int maxR, int floor, int ceiling) {

    /** A set holding one player, who accepts themselves. */
    static Overlap of(Player player, int radius) {
        return new Overlap(player.rating(), player.rating(),
                player.rating() - radius, player.rating() + radius);
    }

    /** The set with this candidate in it. Says nothing about whether they may join. */
    Overlap extendedBy(Player candidate, int radius) {
        return new Overlap(
                Math.min(minR, candidate.rating()),
                Math.max(maxR, candidate.rating()),
                Math.max(floor, candidate.rating() - radius),
                Math.min(ceiling, candidate.rating() + radius));
    }

    /**
     * Adding a player only widens the span and narrows the reach, so a set that
     * fails here cannot be repaired by adding anyone.
     */
    boolean valid() {
        return floor <= minR && ceiling >= maxR;
    }
}
