-- sent_at is the last time intake published the entry. accepted_at is set when matchmaking
-- confirms it and cleared when it is sent again. rating is what the engine queued it at.
ALTER TABLE entries
    ADD COLUMN sent_at     timestamptz,
    ADD COLUMN accepted_at timestamptz,
    ADD COLUMN rating      integer;

UPDATE entries SET sent_at = queued_at;
ALTER TABLE entries ALTER COLUMN sent_at SET NOT NULL;

-- The sweeper looks for entries still unconfirmed.
CREATE INDEX entries_unaccepted ON entries (sent_at) WHERE accepted_at IS NULL;

-- One row: when intake last heard matchmaking's heartbeat, by intake's own clock.
CREATE TABLE heartbeat (
    id        smallint PRIMARY KEY CHECK (id = 1),
    last_seen timestamptz NOT NULL
);

-- The latest heartbeat's waits by rating band, replaced whole on each heartbeat.
CREATE TABLE wait_bands (
    band               integer PRIMARY KEY,
    total_wait_seconds double precision NOT NULL,
    seats              bigint NOT NULL
);
