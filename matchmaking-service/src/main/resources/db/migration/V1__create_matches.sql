CREATE TABLE players (
    id     uuid PRIMARY KEY,
    rating integer NOT NULL
);

-- winner and ended_at stay null until a result is recorded.
CREATE TABLE matches (
    id        uuid PRIMARY KEY,
    formed_at timestamptz NOT NULL,
    winner    char(1) CHECK (winner IN ('A', 'B')),
    ended_at  timestamptz
);

-- The estimated wait reads matches formed in the last hour.
CREATE INDEX matches_formed_at ON matches (formed_at);

-- rating_before is kept because players.rating is overwritten by every result.
CREATE TABLE player_matches (
    match_id      uuid NOT NULL REFERENCES matches (id),
    player_id     uuid NOT NULL REFERENCES players (id),
    side          char(1) NOT NULL CHECK (side IN ('A', 'B')),
    rating_before integer NOT NULL,
    queued_at     timestamptz NOT NULL,
    PRIMARY KEY (match_id, player_id)
);

-- The primary key leads with match_id, so a player's history needs its own index.
CREATE INDEX player_matches_player_id ON player_matches (player_id);
