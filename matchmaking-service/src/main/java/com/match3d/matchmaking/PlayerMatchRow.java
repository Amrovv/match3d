package com.match3d.matchmaking;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One player's seat in one match. Ids are plain columns, not associations,
 * so loading a row never fetches a match or a player behind it.
 */
@Entity
@Table(name = "player_matches")
public class PlayerMatchRow {

    @Embeddable
    public record Key(UUID matchId, UUID playerId) {
    }

    @EmbeddedId
    private Key key;

    private char side;

    private int ratingBefore;

    private Instant queuedAt;

    protected PlayerMatchRow() {
    }

    public PlayerMatchRow(UUID matchId, UUID playerId, char side, int ratingBefore, Instant queuedAt) {
        this.key = new Key(matchId, playerId);
        this.side = side;
        this.ratingBefore = ratingBefore;
        this.queuedAt = queuedAt;
    }

    public UUID getMatchId() {
        return key.matchId();
    }

    public UUID getPlayerId() {
        return key.playerId();
    }

    public char getSide() {
        return side;
    }

    public int getRatingBefore() {
        return ratingBefore;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }
}
