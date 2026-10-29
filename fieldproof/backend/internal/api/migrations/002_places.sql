-- Villages can now come from GPS lookups anywhere in India (OpenStreetMap),
-- not only the seeded list.
ALTER TABLE villages ADD COLUMN IF NOT EXISTS lat double precision;
ALTER TABLE villages ADD COLUMN IF NOT EXISTS lng double precision;
ALTER TABLE villages ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'seed';
