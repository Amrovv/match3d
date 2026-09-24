-- Local runs and tests only, through the local profile. Ids 00000000-0000-0000-0000-000000000001 to ...020.
INSERT INTO players (id, rating)
SELECT ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, 2500
FROM generate_series(1, 20) AS n
ON CONFLICT (id) DO NOTHING;
