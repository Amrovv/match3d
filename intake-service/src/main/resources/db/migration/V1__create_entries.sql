-- One row per queued entry. queued_at is kept so a requeue can keep its place.
CREATE TABLE entries (
    id        uuid PRIMARY KEY,
    queued_at timestamptz NOT NULL
);

-- A player is queued, matched, refused, or none; the checks keep it to one.
-- rejection holds an EntryRejected.Reason name.
CREATE TABLE players (
    id        uuid PRIMARY KEY,
    entry_id  uuid REFERENCES entries (id),
    match_id  uuid,
    side      char(1) CHECK (side IN ('A', 'B')),
    rejection varchar(32),
    CHECK ((match_id IS NULL) = (side IS NULL)),
    CHECK ((entry_id IS NOT NULL)::int + (match_id IS NOT NULL)::int + (rejection IS NOT NULL)::int <= 1)
);

-- An entry's members, for leave, match and requeue.
CREATE INDEX players_entry_id ON players (entry_id);

-- A match's players, for status and match ended.
CREATE INDEX players_match_id ON players (match_id);
