package com.match3d.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A player waiting in the queue.
 * Immutable, several workers can hold references to the same player object. 
 * Identity is the id alone.
 */
public record Player(UUID id, int rating, Instant queuedAt) {

    public Player {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(queuedAt, "queuedAt");
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
