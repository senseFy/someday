CREATE TABLE someday_media_v3_objects (
    user_id UUID NOT NULL REFERENCES someday_users (id) ON DELETE CASCADE,
    workspace_id TEXT NOT NULL CHECK (workspace_id ~ '^workspace-[0-9a-f]{32}$'),
    media_id TEXT NOT NULL CHECK (media_id ~ '^[0-9a-f]{64}$'),
    ciphertext_bytes INTEGER NOT NULL CHECK (ciphertext_bytes BETWEEN 45 AND 4198444),
    ciphertext_sha256 TEXT NOT NULL CHECK (ciphertext_sha256 ~ '^sha256:[0-9a-f]{64}$'),
    uploaded_by_device_id UUID NOT NULL REFERENCES someday_devices (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, workspace_id, media_id),
    FOREIGN KEY (user_id, workspace_id)
        REFERENCES someday_entity_workspaces (user_id, workspace_id) ON DELETE CASCADE
);

CREATE INDEX someday_media_v3_objects_account_quota_idx
    ON someday_media_v3_objects (user_id, ciphertext_bytes);

ALTER TABLE someday_media_v3_objects ENABLE ROW LEVEL SECURITY;
ALTER TABLE someday_media_v3_objects FORCE ROW LEVEL SECURITY;
CREATE POLICY media_account_workspace_scope ON someday_media_v3_objects
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
