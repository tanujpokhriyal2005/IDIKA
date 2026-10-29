-- How far the agent is with a check, for the progress bar on the dashboard, and a
-- "stopped" state for checks that were called off before they finished.
ALTER TABLE land_checks ADD COLUMN progress int NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100);
ALTER TABLE land_checks ADD COLUMN parcels_done int NOT NULL DEFAULT 0;
ALTER TABLE land_checks DROP CONSTRAINT land_checks_status_check;
ALTER TABLE land_checks ADD CONSTRAINT land_checks_status_check
    CHECK (status IN ('queued', 'running', 'done', 'failed', 'stopped'));
