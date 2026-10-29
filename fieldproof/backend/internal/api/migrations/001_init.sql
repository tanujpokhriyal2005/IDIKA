CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE villages (
    id        serial PRIMARY KEY,
    district  text NOT NULL,
    block     text NOT NULL,
    name      text NOT NULL,
    UNIQUE (district, block, name)
);

CREATE TABLE surveyors (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone       text NOT NULL UNIQUE,
    name        text NOT NULL,
    district    text NOT NULL,
    home_block  text NOT NULL,
    language    text NOT NULL DEFAULT 'en',
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Blocks a surveyor may register farmers in (village list is limited to these).
CREATE TABLE surveyor_blocks (
    surveyor_id uuid NOT NULL REFERENCES surveyors(id) ON DELETE CASCADE,
    district    text NOT NULL,
    block       text NOT NULL,
    PRIMARY KEY (surveyor_id, district, block)
);

CREATE TABLE otp_codes (
    phone       text PRIMARY KEY,
    code_hash   text NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    int NOT NULL DEFAULT 0,
    sent_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sessions (
    token_hash  text PRIMARY KEY,
    surveyor_id uuid NOT NULL REFERENCES surveyors(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL
);

-- Farmer numbers are handed to phones in blocks so farmer IDs can be shown
-- offline the moment a farmer is registered.
CREATE SEQUENCE farmer_number_seq START 4400;

CREATE TABLE farmer_numbers (
    number      int PRIMARY KEY,
    surveyor_id uuid NOT NULL REFERENCES surveyors(id),
    reserved_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE farmers (
    id                  uuid PRIMARY KEY,
    number              int NOT NULL UNIQUE,
    code                text NOT NULL UNIQUE,
    surveyor_id         uuid NOT NULL REFERENCES surveyors(id),
    full_name           text NOT NULL,
    mobile              text NOT NULL,
    whatsapp_same       boolean NOT NULL DEFAULT false,
    whatsapp            text NOT NULL DEFAULT '',
    other_mobile        text NOT NULL DEFAULT '',
    village_id          int NOT NULL REFERENCES villages(id),
    tree_interest       boolean NOT NULL,
    aadhaar_name_match  boolean NOT NULL DEFAULT false,
    aadhaar_consent     boolean NOT NULL DEFAULT false,
    status              text NOT NULL DEFAULT 'registered',
    farm_details        jsonb,
    submitted_at        timestamptz,
    client_created_at   timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX farmers_surveyor_idx ON farmers(surveyor_id);

CREATE TABLE lands (
    id               uuid PRIMARY KEY,
    farmer_id        uuid NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    idx              int NOT NULL,
    survey_no        text NOT NULL,
    area_ha          numeric(10,4) NOT NULL,
    register_acres   numeric(10,2) NOT NULL,
    owner_type       text NOT NULL,
    records_consent  boolean NOT NULL
);
CREATE INDEX lands_farmer_idx ON lands(farmer_id);

CREATE TABLE files (
    id           uuid PRIMARY KEY,
    farmer_id    uuid NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    land_id      uuid,
    kind         text NOT NULL,
    path         text NOT NULL,
    mime         text NOT NULL,
    size_bytes   bigint NOT NULL,
    sha256       text NOT NULL,
    lat          double precision,
    lng          double precision,
    accuracy_m   double precision,
    captured_at  timestamptz,
    sharpness    double precision,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX files_farmer_idx ON files(farmer_id);

CREATE TABLE boundaries (
    id              uuid PRIMARY KEY,
    farmer_id       uuid NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    land_id         uuid,
    geom            geography(Polygon, 4326) NOT NULL,
    points          jsonb NOT NULL,
    point_count     int NOT NULL,
    area_m2         double precision NOT NULL,
    client_area_m2  double precision,
    walked_m        double precision,
    accuracy_m      double precision,
    started_at      timestamptz,
    finished_at     timestamptz,
    status          text NOT NULL DEFAULT 'active',
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX boundaries_geom_idx ON boundaries USING gist (geom);
CREATE INDEX boundaries_farmer_idx ON boundaries(farmer_id);

CREATE TABLE action_items (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    surveyor_id  uuid NOT NULL REFERENCES surveyors(id),
    farmer_id    uuid NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    kind         text NOT NULL,
    note         text NOT NULL DEFAULT '',
    status       text NOT NULL DEFAULT 'open',
    created_at   timestamptz NOT NULL DEFAULT now(),
    done_at      timestamptz
);
CREATE UNIQUE INDEX action_items_open_uniq ON action_items(farmer_id, kind) WHERE status = 'open';
