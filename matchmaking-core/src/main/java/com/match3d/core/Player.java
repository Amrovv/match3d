package com.match3d.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A player waiting in the queue. Immutable, so several workers may hold the
 * same object. Identity is the id alone, so a player rebuilt elsewhere with a
 * different timestamp is still the same player.
 */
public record Player(UUID id, int rating, Instant queuedAt) implements QueueEntry {

    public Player {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(queuedAt, "queuedAt");
    }

    /** A player takes one spot. */
    @Override
    public int size() {
        return 1;
    }

    /** Just themselves, so a caller need not know which kind of entry it holds. */
    @Override
    public List<Player> members() {
        return List.of(this);
    }

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
