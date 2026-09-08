package com.match3d.core;

/**
 * Whether every player in a set accepts every other, in four numbers.
 *
 * minR and maxR are the span of accepted ratings. floor and ceiling are the
 * narrowest reach among them, the largest rating minus radius and the smallest
 * rating plus radius. The set is valid when the reach covers the span, which is
 * the pairwise condition without the pairs, because an interval is contiguous.
 *
 * The members are never revisited, so a candidate costs O(1) to test.
 *
 * Immutable. A rejected candidate leaves nothing to undo.
 */
record Overlap(int minR, int maxR, int floor, int ceiling) {

    /**
     * A set holding one player, who accepts themselves.
     */
    static Overlap of(Player player, int radius) {
        return new Overlap(player.rating(), player.rating(),
                player.rating() - radius, player.rating() + radius);
    }

    /**
     * What the set would look like with this candidate in it.
     *
     * Says nothing about whether they may join. valid answers that.
     */
    Overlap extendedBy(Player candidate, int radius) {
        return new Overlap(
                Math.min(minR, candidate.rating()),
                Math.max(maxR, candidate.rating()),
                Math.max(floor, candidate.rating() - radius),
                Math.min(ceiling, candidate.rating() + radius));
    }

    /**
     * Whether every member reaches every other member.
     *
     * Adding a player can only widen the span and narrow the reach, so a set
     * that fails here cannot be repaired by adding anyone.
     */
    boolean valid() {
        return floor <= minR && ceiling >= maxR;
    }
}
