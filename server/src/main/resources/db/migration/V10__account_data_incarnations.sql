-- All backfills, constraints and the account-creation trigger commit together.
SELECT set_config('someday.user_id', '*', true);
SELECT set_config('someday.workspace_id', '*', true);
LOCK TABLE someday_users IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE someday_account_data_incarnations (
    user_id UUID NOT NULL REFERENCES someday_users(id) ON DELETE CASCADE,
    incarnation UUID NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active', 'retired')),
    storage_layout TEXT NOT NULL CHECK (storage_layout IN ('legacy', 'incarnation-v1')),
    created_at TIMESTAMPTZ NOT NULL,
    retired_at TIMESTAMPTZ,
    media_reclaimed_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, incarnation),
    CHECK (
        (state = 'active' AND retired_at IS NULL AND media_reclaimed_at IS NULL)
        OR (state = 'retired' AND retired_at IS NOT NULL)
    ),
    CHECK (
        (incarnation = '00000000-0000-0000-0000-000000000000'::uuid)
        = (storage_layout = 'legacy')
    )
);

CREATE UNIQUE INDEX someday_account_data_incarnations_one_active_idx
    ON someday_account_data_incarnations(user_id) WHERE state = 'active';
CREATE INDEX someday_account_data_incarnations_unreclaimed_idx
    ON someday_account_data_incarnations(user_id)
    WHERE state = 'retired' AND media_reclaimed_at IS NULL;

-- Account-only authority tables follow auth-table ownership, without workspace RLS.
CREATE FUNCTION public.someday_initialize_account_incarnation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO public.someday_account_data_incarnations (
        user_id, incarnation, state, storage_layout, created_at
    ) VALUES (
        NEW.id, '00000000-0000-0000-0000-000000000000', 'active', 'legacy', NEW.created_at
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER someday_users_initialize_incarnation
    AFTER INSERT ON someday_users
    FOR EACH ROW EXECUTE FUNCTION public.someday_initialize_account_incarnation();

INSERT INTO someday_account_data_incarnations (
    user_id, incarnation, state, storage_layout, created_at
)
SELECT id, '00000000-0000-0000-0000-000000000000', 'active', 'legacy', created_at
FROM someday_users;

ALTER TABLE someday_sessions ADD COLUMN data_incarnation UUID;
ALTER TABLE someday_devices ADD COLUMN data_incarnation UUID;
ALTER TABLE someday_entity_workspaces ADD COLUMN data_incarnation UUID;
ALTER TABLE workspace_pairing_invites ADD COLUMN data_incarnation UUID;

UPDATE someday_sessions SET data_incarnation = '00000000-0000-0000-0000-000000000000';
UPDATE someday_devices SET data_incarnation = '00000000-0000-0000-0000-000000000000';
UPDATE someday_entity_workspaces SET data_incarnation = '00000000-0000-0000-0000-000000000000';
UPDATE workspace_pairing_invites SET data_incarnation = '00000000-0000-0000-0000-000000000000';

ALTER TABLE someday_sessions ALTER COLUMN data_incarnation SET NOT NULL;
ALTER TABLE someday_devices ALTER COLUMN data_incarnation SET NOT NULL;
ALTER TABLE someday_entity_workspaces ALTER COLUMN data_incarnation SET NOT NULL;
ALTER TABLE workspace_pairing_invites ALTER COLUMN data_incarnation SET NOT NULL;

ALTER TABLE someday_sessions ADD CONSTRAINT someday_sessions_incarnation_fk
    FOREIGN KEY (user_id, data_incarnation)
    REFERENCES someday_account_data_incarnations(user_id, incarnation);
ALTER TABLE someday_devices ADD CONSTRAINT someday_devices_incarnation_fk
    FOREIGN KEY (user_id, data_incarnation)
    REFERENCES someday_account_data_incarnations(user_id, incarnation);
ALTER TABLE someday_entity_workspaces ADD CONSTRAINT someday_entity_workspaces_incarnation_fk
    FOREIGN KEY (user_id, data_incarnation)
    REFERENCES someday_account_data_incarnations(user_id, incarnation);
ALTER TABLE workspace_pairing_invites ADD CONSTRAINT workspace_pairing_invites_incarnation_fk
    FOREIGN KEY (user_id, data_incarnation)
    REFERENCES someday_account_data_incarnations(user_id, incarnation);

-- Device ownership is independent of mutable device enrollment. In particular,
-- re-enrollment must never cascade a new incarnation into a historical session.
ALTER TABLE someday_devices ADD CONSTRAINT someday_devices_account_id_key UNIQUE (user_id, id);
ALTER TABLE someday_sessions DROP CONSTRAINT someday_sessions_device_id_fkey;
ALTER TABLE someday_sessions ADD CONSTRAINT someday_sessions_account_device_fk
    FOREIGN KEY (user_id, device_id) REFERENCES someday_devices(user_id, id)
    ON DELETE SET NULL (device_id);
ALTER TABLE workspace_pairing_invites DROP CONSTRAINT workspace_pairing_invites_creator_device_id_fkey;
ALTER TABLE workspace_pairing_invites ADD CONSTRAINT workspace_pairing_invites_account_creator_fk
    FOREIGN KEY (user_id, creator_device_id) REFERENCES someday_devices(user_id, id) ON DELETE CASCADE;
ALTER TABLE workspace_pairing_invites DROP CONSTRAINT workspace_pairing_invites_claim_device_id_fkey;
ALTER TABLE workspace_pairing_invites ADD CONSTRAINT workspace_pairing_invites_account_claimant_fk
    FOREIGN KEY (user_id, claim_device_id) REFERENCES someday_devices(user_id, id) ON DELETE RESTRICT;
ALTER TABLE someday_sync_v2_objects DROP CONSTRAINT someday_sync_v2_objects_first_writer_device_id_fkey;
ALTER TABLE someday_sync_v2_objects ADD CONSTRAINT someday_sync_v2_objects_account_writer_fk
    FOREIGN KEY (user_id, first_writer_device_id) REFERENCES someday_devices(user_id, id) ON DELETE RESTRICT;
ALTER TABLE someday_media_v3_objects DROP CONSTRAINT someday_media_v3_objects_uploaded_by_device_id_fkey;
ALTER TABLE someday_media_v3_objects ADD CONSTRAINT someday_media_v3_objects_account_uploader_fk
    FOREIGN KEY (user_id, uploaded_by_device_id) REFERENCES someday_devices(user_id, id) ON DELETE RESTRICT;
ALTER TABLE workspace_recovery_envelopes DROP CONSTRAINT workspace_recovery_envelopes_created_by_device_id_fkey;
ALTER TABLE workspace_recovery_envelopes ADD CONSTRAINT workspace_recovery_envelopes_account_creator_fk
    FOREIGN KEY (user_id, created_by_device_id) REFERENCES someday_devices(user_id, id)
    ON DELETE SET NULL (created_by_device_id);
ALTER TABLE workspace_recovery_envelopes DROP CONSTRAINT workspace_recovery_envelopes_updated_by_device_id_fkey;
ALTER TABLE workspace_recovery_envelopes ADD CONSTRAINT workspace_recovery_envelopes_account_updater_fk
    FOREIGN KEY (user_id, updated_by_device_id) REFERENCES someday_devices(user_id, id)
    ON DELETE SET NULL (updated_by_device_id);

CREATE INDEX someday_sessions_account_incarnation_idx ON someday_sessions(user_id, data_incarnation);
CREATE INDEX someday_entity_workspaces_account_incarnation_idx
    ON someday_entity_workspaces(user_id, data_incarnation, workspace_id);
CREATE INDEX workspace_pairing_invites_account_incarnation_idx
    ON workspace_pairing_invites(user_id, data_incarnation, state, expires_at);

CREATE TABLE someday_account_data_resets (
    user_id UUID NOT NULL REFERENCES someday_users(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    protocol_version INTEGER NOT NULL CHECK (protocol_version = 1),
    expected_incarnation UUID NOT NULL,
    new_incarnation UUID NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, operation_id),
    UNIQUE (user_id, new_incarnation),
    CHECK (expected_incarnation <> new_incarnation),
    FOREIGN KEY (user_id, expected_incarnation)
        REFERENCES someday_account_data_incarnations(user_id, incarnation),
    FOREIGN KEY (user_id, new_incarnation)
        REFERENCES someday_account_data_incarnations(user_id, incarnation)
);

CREATE INDEX someday_account_data_resets_account_committed_idx
    ON someday_account_data_resets(user_id, committed_at);
