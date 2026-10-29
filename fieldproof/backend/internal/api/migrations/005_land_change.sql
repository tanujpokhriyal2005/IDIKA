-- Land swap check: the boundary walked just before planting is compared with
-- the land checked at onboarding. A walk mostly on other land needs a land
-- change that an NGO, state lead or admin approves before planting continues.

ALTER TABLE plantations
    ADD COLUMN pre_overlap             double precision,   -- share of the pre-plant walk on the onboarding land (20 m allowance)
    ADD COLUMN pre_distance_m          double precision,   -- between the middles of the two
    ADD COLUMN land_note               text NOT NULL DEFAULT '',  -- why a walk partly off the onboarding land is fine
    ADD COLUMN land_change_status      text CHECK (land_change_status IN ('waiting', 'approved', 'rejected', 'pending')),
    ADD COLUMN land_change_reason      text NOT NULL DEFAULT '',
    ADD COLUMN land_change_survey_no   text NOT NULL DEFAULT '',
    ADD COLUMN land_change_area_ha     double precision,
    ADD COLUMN land_change_owner       text NOT NULL DEFAULT '',
    ADD COLUMN land_change_at          timestamptz,
    ADD COLUMN land_change_message     text NOT NULL DEFAULT '',
    ADD COLUMN land_change_reviewer_id uuid REFERENCES users(id),
    ADD COLUMN land_change_reviewed_at timestamptz;

CREATE OR REPLACE VIEW farmer_plan_status AS
SELECT x.farmer_id, x.onboarding_ok,
    CASE WHEN NOT x.onboarding_ok AND x.pay IS NULL THEN 'locked' ELSE coalesce(x.pay, 'none') END AS payment,
    CASE
        WHEN NOT x.onboarding_ok OR x.pay IS DISTINCT FROM 'approved' THEN 'locked'
        WHEN pl.pre_boundary_id IS NULL THEN 'pre_walk'
        WHEN pl.land_change_status = 'waiting' THEN 'land_review'
        WHEN pl.land_change_status = 'pending' THEN 'land_fix'
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
