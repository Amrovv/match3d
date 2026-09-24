package com.match3d.intake;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** What one player is doing. At most one of entry, match and rejection is set. */
@Entity
@Table(name = "players")
public class PlayerRow {

    @Id
    private UUID id;

    private UUID entryId;

    private UUID matchId;

    private Character side;

    private String rejection;

    protected PlayerRow() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public UUID getMatchId() {
        return matchId;
    }

    public Character getSide() {
        return side;
    }

    public String getRejection() {
        return rejection;
    }
}
