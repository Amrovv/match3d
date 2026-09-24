package com.match3d.intake;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One queued entry. Its members point at it from players. */
@Entity
@Table(name = "entries")
public class EntryRow {

    @Id
    private UUID id;

    private Instant queuedAt;

    protected EntryRow() {
    }

    public EntryRow(UUID id, Instant queuedAt) {
        this.id = id;
        this.queuedAt = queuedAt;
    }

    public UUID getId() {
        return id;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }
}
