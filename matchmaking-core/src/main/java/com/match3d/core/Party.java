package com.match3d.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Players who queued together and are matched together or not at all. One id,
 * one rating and one queue time however many of them there are, so the queue
 * holds a party as a single entry.
 *
 * The rating comes from the members and is pulled toward the strongest, so a
 * strong player queueing with weaker friends does not get easier games.
 *
 * Members cannot change while the party is queued. Adding or losing someone
 * means building a new party and queueing again, so the rating can never go
 * out of date.
 */
public record Party(UUID id, List<Player> members, Instant queuedAt, int rating)
        implements QueueEntry {

    /** Lobbies are 5v5, a party cannot exceed 5 members. */
    static final int MAX_SIZE = 5;

    /** A party is defined to have at least 2 players. */
    static final int MIN_SIZE = 2;

    /** Widest gap allowed between two members, checked when a party is built. */
    static final int MAX_SPREAD = 2500;

    /** How far the rating is pulled from the mean toward the strongest member. */
    static final double SHIFT = 0.5;

    public Party {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(queuedAt, "queuedAt");
        if (members.size() < MIN_SIZE) throw new IllegalArgumentException("Party is too small");
        if (members.size() > MAX_SIZE) throw new IllegalArgumentException("Party is too large");

        members = List.copyOf(members);

        int max = members.stream()
                         .mapToInt(Player::rating)
                         .max()
                         .orElseThrow();

        int min = members.stream()
                         .mapToInt(Player::rating)
                         .min()
                         .orElseThrow();

        if (max - min > MAX_SPREAD) {
            throw new IllegalArgumentException("Party is spread too wide");
        }

        // Without this, a party could be built whose rating does not match
        // its members, and nothing later would notice.
        if (rating != ratingOf(members)) {
            throw new IllegalArgumentException("Party rating disagrees with its members");
        }
    }

    /**
     * The way to build one. It works out the rating, so a caller cannot get
     * it wrong.
     *
     * A new id every time, so a party rebuilt after someone leaves is not the
     * party that was queued.
     */
    public static Party of(List<Player> members, Instant queuedAt) {
        return new Party(UUID.randomUUID(), members, queuedAt, ratingOf(members));
    }

    /** mean + SHIFT * (max - mean), floored. */
    static int ratingOf(List<Player> members) {
        // of works out the rating before the constructor runs, so an empty
        // list has to fail here too.
        if (members.isEmpty()) throw new IllegalArgumentException("Party has no members");

        double mean = members.stream()
                             .mapToInt(Player::rating)
                             .average()
                             .orElseThrow();

        int max = members.stream()
                         .mapToInt(Player::rating)
                         .max()
                         .orElseThrow();

        return (int) Math.floor(mean + SHIFT * (max - mean));
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Party other) {
            return this.id.equals(other.id);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Seats this party takes. */
    @Override
    public int size() {
        return members.size();
    }
}
