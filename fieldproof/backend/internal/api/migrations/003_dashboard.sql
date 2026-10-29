-- Web dashboard: people at each level (admin → state lead → NGO → surveyor),
-- per-document review of onboarded farmers, and review tasks sent back to the
-- surveyor's phone.

CREATE TABLE users (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username              text NOT NULL,
    password_hash         text NOT NULL,
    role                  text NOT NULL CHECK (role IN ('admin', 'state_lead', 'ngo', 'surveyor')),
    full_name             text NOT NULL,
    mobile                text NOT NULL DEFAULT '',
    state                 text,
    district              text NOT NULL DEFAULT '',
    blocks                text[] NOT NULL DEFAULT '{}',
    ngo_id                uuid REFERENCES users(id),
    surveyor_id           uuid UNIQUE REFERENCES surveyors(id) ON DELETE CASCADE,
    created_by            uuid REFERENCES users(id),
    must_change_password  boolean NOT NULL DEFAULT true,
    active                boolean NOT NULL DEFAULT true,
    failed_logins         int NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    last_login_at         timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    CHECK (username ~ '^[a-z0-9]([a-z0-9.]{1,30})[a-z0-9]$' AND username NOT LIKE '%..%'),
    CHECK (role = 'admin' OR state IS NOT NULL),
    CHECK (role <> 'surveyor' OR (ngo_id IS NOT NULL AND surveyor_id IS NOT NULL))
);
CREATE UNIQUE INDEX users_username_uniq ON users (lower(username));
CREATE INDEX users_ngo_idx ON users (ngo_id);
CREATE INDEX users_state_idx ON users (state);

CREATE TABLE dash_sessions (
    token_hash  text PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL
);

-- Where a surveyor sits in the hierarchy. Set when the login is issued.
ALTER TABLE surveyors ADD COLUMN ngo_user_id uuid REFERENCES users(id);
ALTER TABLE surveyors ADD COLUMN state text;
ALTER TABLE surveyors ADD COLUMN last_seen_at timestamptz;
CREATE INDEX surveyors_ngo_idx ON surveyors (ngo_user_id);

-- Current review status of each document. No row = never reviewed
-- (shown as New when the document exists, Not uploaded when it doesn't).
CREATE TABLE farmer_docs (
    farmer_id   uuid NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    doc         text NOT NULL CHECK (doc IN ('aadhaar', 'land_record', 'water_source', 'boundary')),
    status      text NOT NULL CHECK (status IN ('new', 'approved', 'rejected', 'pending')),
    message     text NOT NULL DEFAULT '',
    reviewer_id uuid REFERENCES users(id),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (farmer_id, doc)
);

-- Every decision and every re-upload, newest last. Nothing is ever deleted.
CREATE TABLE doc_events (
    id          bigserial PRIMARY KEY,
    farmer_id   uuid NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    doc         text NOT NULL,
    event       text NOT NULL CHECK (event IN ('approved', 'rejected', 'pending', 'reuploaded')),
    message     text NOT NULL DEFAULT '',
    user_id     uuid REFERENCES users(id),
    surveyor_id uuid REFERENCES surveyors(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX doc_events_farmer_idx ON doc_events (farmer_id, created_at);

-- Review tasks on the phone carry the reviewer's message.
ALTER TABLE action_items ADD COLUMN doc text;
ALTER TABLE action_items ADD COLUMN reviewer_name text NOT NULL DEFAULT '';
ALTER TABLE action_items ADD COLUMN decision text NOT NULL DEFAULT '';
ALTER TABLE action_items ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- Status of the four documents for every farmer, derived from uploads and reviews.
CREATE VIEW farmer_doc_status AS
SELECT f.id AS farmer_id,
    COALESCE((SELECT d.status FROM farmer_docs d WHERE d.farmer_id = f.id AND d.doc = 'aadhaar'),
        CASE WHEN EXISTS (SELECT 1 FROM files x WHERE x.farmer_id = f.id AND x.kind IN ('aadhaar_front', 'aadhaar_back')) THEN 'new' ELSE 'missing' END) AS aadhaar,
    COALESCE((SELECT d.status FROM farmer_docs d WHERE d.farmer_id = f.id AND d.doc = 'land_record'),
        CASE WHEN EXISTS (SELECT 1 FROM files x WHERE x.farmer_id = f.id AND x.kind IN ('land_record', 'land_record_pdf')) THEN 'new' ELSE 'missing' END) AS land_record,
    COALESCE((SELECT d.status FROM farmer_docs d WHERE d.farmer_id = f.id AND d.doc = 'water_source'),
        CASE WHEN EXISTS (SELECT 1 FROM files x WHERE x.farmer_id = f.id AND x.kind = 'water_source') THEN 'new' ELSE 'missing' END) AS water_source,
    COALESCE((SELECT d.status FROM farmer_docs d WHERE d.farmer_id = f.id AND d.doc = 'boundary'),
        CASE WHEN EXISTS (SELECT 1 FROM boundaries b WHERE b.farmer_id = f.id AND b.status = 'active') THEN 'new' ELSE 'missing' END) AS boundary
FROM farmers f;

CREATE INDEX files_farmer_kind_idx ON files (farmer_id, kind);
CREATE INDEX farmers_submitted_idx ON farmers (submitted_at) WHERE status = 'submitted';
