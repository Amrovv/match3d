package com.match3d.matchmaking;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A player's current rating. Overwritten by every result. */
@Entity
@Table(name = "players")
public class PlayerRow {

    @Id
    private UUID id;

    private int rating;

    protected PlayerRow() {
    }

    public PlayerRow(UUID id, int rating) {
        this.id = id;
        this.rating = rating;
    }

    public UUID getId() {
        return id;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = rating;
    }
}
