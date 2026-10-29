-- Land check: a dashboard user uploads a KML of parcels; the land check agent
-- (landcheck-agent/worker.py, which has Google Earth Engine access) takes queued
-- checks from this table, checks each parcel's satellite history since 2017 and
-- stores a self-contained HTML report here.
CREATE TABLE land_checks (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text NOT NULL,
    file_name    text NOT NULL,
    kml          text NOT NULL,
    parcels      int  NOT NULL,
    status       text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'failed')),
    error        text NOT NULL DEFAULT '',
    summary      jsonb,                -- verdict -> number of parcels, e.g. {"Flag": 2, "Approve": 3}
    report       text,
    created_by   uuid NOT NULL REFERENCES users(id),
    state        text,                 -- the uploader's state, so their state lead sees the check
    created_at   timestamptz NOT NULL DEFAULT now(),
    started_at   timestamptz,
    finished_at  timestamptz
);
CREATE INDEX land_checks_queue_idx ON land_checks (status, created_at);
CREATE INDEX land_checks_creator_idx ON land_checks (created_by);

-- When the agent last checked in, so the dashboard can say whether it is running.
CREATE TABLE land_check_agent (
    id       int PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    seen_at  timestamptz NOT NULL
);
