-- A check can be paused (and resumed later, which starts it again from the beginning).
ALTER TABLE land_checks DROP CONSTRAINT land_checks_status_check;
ALTER TABLE land_checks ADD CONSTRAINT land_checks_status_check
    CHECK (status IN ('queued', 'running', 'done', 'failed', 'stopped', 'paused'));
