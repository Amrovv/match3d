package com.match3d.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.Comparator;

/**
 * A player waiting in the queue. Immutable, so several workers may hold the
 * same object. Identity is the id alone, so a player rebuilt elsewhere with a
 * different timestamp is still the same player.
 */
public record Player(UUID id, int rating, Instant queuedAt) {

    public Player {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(queuedAt, "queuedAt");
    }

    static final Comparator<Player> BY_WAIT_TIME =
            Comparator.comparing(Player::queuedAt).thenComparing(Player::id);

    @Override
    public boolean equals(Object o) {
        if (o instanceof Player other) {
            return this.id.equals(other.id);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

}
