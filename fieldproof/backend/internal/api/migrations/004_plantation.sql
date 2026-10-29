-- Payment and plantation: the farmer pays ₹5 per timber and ₹10 per fruit
-- sapling (2 m × 2 m spacing); a reviewer approves the payment; the surveyor
-- walks the boundary before planting, the farmer counts the delivered
-- saplings and confirms with an OTP, pits are dug and saplings planted, the
-- boundary is walked again, and a reviewer approves the plantation.

-- Walks now have a purpose. Every existing query keeps reading onboarding walks.
ALTER TABLE boundaries ADD COLUMN purpose text NOT NULL DEFAULT 'onboarding'
    CHECK (purpose IN ('onboarding', 'pre_plant', 'post_plant'));

CREATE TABLE payments (
    farmer_id     uuid PRIMARY KEY REFERENCES farmers(id) ON DELETE CASCADE,
    surveyor_id   uuid NOT NULL REFERENCES surveyors(id),
    timber_count  int NOT NULL CHECK (timber_count >= 0),
    fruit_count   int NOT NULL CHECK (fruit_count >= 0),
    timber_rate   int NOT NULL DEFAULT 5,
    fruit_rate    int NOT NULL DEFAULT 10,
    amount_due    int NOT NULL CHECK (amount_due > 0),
    amount_paid   int NOT NULL CHECK (amount_paid > 0),
    method        text NOT NULL CHECK (method IN ('upi', 'bank')),
    reference     text NOT NULL,
    paid_on       date NOT NULL,
    status        text NOT NULL CHECK (status IN ('waiting', 'approved', 'rejected', 'pending')),
    message       text NOT NULL DEFAULT '',
    reviewer_id   uuid REFERENCES users(id),
    reviewed_at   timestamptz,
    submitted_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (timber_count + fruit_count > 0)
);
CREATE INDEX payments_reference_idx ON payments (lower(reference));

CREATE TABLE plantations (
    farmer_id         uuid PRIMARY KEY REFERENCES farmers(id) ON DELETE CASCADE,
    pre_boundary_id   uuid REFERENCES boundaries(id),
    post_boundary_id  uuid REFERENCES boundaries(id),
    timber_counted    int,
    fruit_counted     int,
    healthy           boolean NOT NULL DEFAULT false,
    right_species     boolean NOT NULL DEFAULT false,
    satisfied         boolean NOT NULL DEFAULT false,
    short             boolean NOT NULL DEFAULT false,
    delivery_at       timestamptz,          -- set only when the count matched and the farmer was satisfied
    otp_hash          text,
    otp_expires_at    timestamptz,
    otp_attempts      int NOT NULL DEFAULT 0,
    otp_sent_at       timestamptz,
    otp_verified_at   timestamptz,
    otp_lat           double precision,
    otp_lng           double precision,
    timber_planted    int,
    fruit_planted     int,
    planted_at        timestamptz,
    review_status     text CHECK (review_status IN ('approved', 'rejected', 'pending')),
    review_message    text NOT NULL DEFAULT '',
    reviewer_id       uuid REFERENCES users(id),
    reviewed_at       timestamptz,
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- One history for both, newest last. Nothing is deleted.
CREATE TABLE plan_events (
    id          bigserial PRIMARY KEY,
    farmer_id   uuid NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    event       text NOT NULL,
    detail      text NOT NULL DEFAULT '',
    user_id     uuid REFERENCES users(id),
    surveyor_id uuid REFERENCES surveyors(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX plan_events_farmer_idx ON plan_events (farmer_id, created_at);

-- The onboarding boundary document counts only onboarding walks.
CREATE OR REPLACE VIEW farmer_doc_status AS
SELECT f.id AS farmer_id,
    COALESCE((SELECT d.status FROM farmer_docs d WHERE d.farmer_id = f.id AND d.doc = 'aadhaar'),
        CASE WHEN EXISTS (SELECT 1 FROM files x WHERE x.farmer_id = f.id AND x.kind IN ('aadhaar_front', 'aadhaar_back')) THEN 'new' ELSE 'missing' END) AS aadhaar,
    COALESCE((SELECT d.status FROM farmer_docs d WHERE d.farmer_id = f.id AND d.doc = 'land_record'),
        CASE WHEN EXISTS (SELECT 1 FROM files x WHERE x.farmer_id = f.id AND x.kind IN ('land_record', 'land_record_pdf')) THEN 'new' ELSE 'missing' END) AS land_record,
    COALESCE((SELECT d.status FROM farmer_docs d WHERE d.farmer_id = f.id AND d.doc = 'water_source'),
        CASE WHEN EXISTS (SELECT 1 FROM files x WHERE x.farmer_id = f.id AND x.kind = 'water_source') THEN 'new' ELSE 'missing' END) AS water_source,
    COALESCE((SELECT d.status FROM farmer_docs d WHERE d.farmer_id = f.id AND d.doc = 'boundary'),
        CASE WHEN EXISTS (SELECT 1 FROM boundaries b WHERE b.farmer_id = f.id AND b.status = 'active' AND b.purpose = 'onboarding') THEN 'new' ELSE 'missing' END) AS boundary
FROM farmers f;

-- Where each farmer is after onboarding. Each step opens only when the one before is done.
CREATE VIEW farmer_plan_status AS
SELECT x.farmer_id, x.onboarding_ok,
    CASE WHEN NOT x.onboarding_ok AND x.pay IS NULL THEN 'locked' ELSE coalesce(x.pay, 'none') END AS payment,
    CASE
        WHEN NOT x.onboarding_ok OR x.pay IS DISTINCT FROM 'approved' THEN 'locked'
        WHEN pl.pre_boundary_id IS NULL THEN 'pre_walk'
        WHEN pl.short THEN 'short'
        WHEN pl.delivery_at IS NULL THEN 'delivery'
        WHEN pl.otp_verified_at IS NULL THEN 'otp'
        WHEN pl.planted_at IS NULL THEN 'planting'
        WHEN pl.post_boundary_id IS NULL THEN 'post_walk'
        WHEN pl.review_status = 'approved' THEN 'done'
        WHEN pl.review_status IN ('rejected', 'pending') THEN pl.review_status
        ELSE 'to_review'
    END AS plantation
FROM (
    SELECT f.id AS farmer_id,
        (f.status = 'submitted' AND ds.aadhaar = 'approved' AND ds.land_record = 'approved'
            AND ds.water_source = 'approved' AND ds.boundary = 'approved') AS onboarding_ok,
        p.status AS pay
    FROM farmers f JOIN farmer_doc_status ds ON ds.farmer_id = f.id LEFT JOIN payments p ON p.farmer_id = f.id
) x LEFT JOIN plantations pl ON pl.farmer_id = x.farmer_id;
