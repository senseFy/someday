-- Maintenance plans are process-local, but their authority must also change
-- after restore and when another audit supersedes a prior run.
CREATE TABLE someday_maintenance_context (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    verification_context UUID NOT NULL
);
INSERT INTO someday_maintenance_context(singleton, verification_context)
VALUES (TRUE, gen_random_uuid());

ALTER TABLE someday_account_data_incarnations
    ADD COLUMN media_audit_id UUID,
    ADD COLUMN media_last_certified_at TIMESTAMPTZ,
    ADD COLUMN media_revalidation_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE someday_account_data_incarnations
    ADD CONSTRAINT someday_incarnation_active_has_no_media_audit CHECK (
        state = 'retired' OR (media_audit_id IS NULL AND media_last_certified_at IS NULL AND NOT media_revalidation_required)
    );
