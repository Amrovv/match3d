package com.match3d.matchmaking;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One formed match. Winner and end time are null until a result is recorded. */
@Entity
@Table(name = "matches")
public class MatchRow {

    @Id
    private UUID id;

    private Instant formedAt;

    private Character winner;

    private Instant endedAt;

    protected MatchRow() {
    }

    public MatchRow(UUID id, Instant formedAt) {
        this.id = id;
        this.formedAt = formedAt;
    }

    public UUID getId() {
        return id;
    }

    public Instant getFormedAt() {
        return formedAt;
    }

    public Character getWinner() {
        return winner;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void end(char winner, Instant endedAt) {
        this.winner = winner;
        this.endedAt = endedAt;
    }
}
