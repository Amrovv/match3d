package com.match3d.intake;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One queued entry. Its members point at it from players. Written only by native statements. */
@Entity
@Table(name = "entries")
public class EntryRow {

    @Id
    private UUID id;

    private Instant queuedAt;

    private Instant sentAt;

    private Instant acceptedAt;

    private Integer rating;

    protected EntryRow() {
    }

    public UUID getId() {
        return id;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    /** The rating the engine queued it at. Null until first accepted. */
    public Integer getRating() {
        return rating;
    }
}
