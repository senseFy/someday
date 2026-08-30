CREATE TABLE workspace_recovery_envelopes (
    user_id UUID PRIMARY KEY REFERENCES someday_users (id) ON DELETE CASCADE,
    workspace_id TEXT NOT NULL CHECK (workspace_id ~ '^workspace-[0-9a-f]{32}$'),
    key_fingerprint TEXT NOT NULL CHECK (key_fingerprint ~ '^[0-9a-f]{32}$'),
    envelope_json TEXT NOT NULL CHECK (octet_length(envelope_json) BETWEEN 1 AND 65536),
    envelope_digest TEXT NOT NULL CHECK (envelope_digest ~ '^[A-Za-z0-9_-]{43}$'),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    created_by_device_id UUID REFERENCES someday_devices (id) ON DELETE SET NULL,
    updated_by_device_id UUID REFERENCES someday_devices (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (user_id, workspace_id)
        REFERENCES someday_entity_workspaces (user_id, workspace_id) ON DELETE CASCADE
);

-- The recovery envelope is an account-current pointer whose workspace may
-- change during a compare-and-set rotation. Repository connections therefore
-- select one exact account and the wildcard workspace scope: cross-account
-- access remains fail-closed while a legitimate A -> B workspace rotation can
-- see the old row and satisfy the new row's WITH CHECK policy.
ALTER TABLE workspace_recovery_envelopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_recovery_envelopes FORCE ROW LEVEL SECURITY;
CREATE POLICY recovery_account_workspace_scope ON workspace_recovery_envelopes
    USING (
        (current_setting('someday.user_id', true) = '*' OR
            user_id::text = current_setting('someday.user_id', true))
        AND
        (current_setting('someday.workspace_id', true) = '*' OR
            workspace_id = current_setting('someday.workspace_id', true))
    )
    WITH CHECK (
        (current_setting('someday.user_id', true) = '*' OR
            user_id::text = current_setting('someday.user_id', true))
        AND
        (current_setting('someday.workspace_id', true) = '*' OR
            workspace_id = current_setting('someday.workspace_id', true))
    );
