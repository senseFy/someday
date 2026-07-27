CREATE TABLE someday_sync_v2_epochs (
    user_id UUID NOT NULL REFERENCES someday_users (id) ON DELETE CASCADE,
    epoch_id TEXT NOT NULL,
    pointer_digest TEXT NOT NULL,
    pointer_object_json TEXT NOT NULL,
    contract_id TEXT NOT NULL CHECK (contract_id = 'someday-system-v2'),
    schema_set_version TEXT NOT NULL CHECK (schema_set_version = 'workspace-entity-schema-set-v2'),
    semantic_protocol_version INTEGER NOT NULL CHECK (semantic_protocol_version = 2),
    minimum_writer_protocol_version INTEGER NOT NULL CHECK (minimum_writer_protocol_version >= 2),
    key_set_version TEXT NOT NULL CHECK (key_set_version = 'sync-key-set-v2'),
    remote_profile TEXT NOT NULL CHECK (remote_profile = 'self-hosted-v2'),
    metadata_privacy_mode TEXT NOT NULL CHECK (metadata_privacy_mode = 'opaque'),
    supported_offline_window_seconds BIGINT NOT NULL CHECK (supported_offline_window_seconds = 15552000),
    checkpoint_id TEXT NOT NULL,
    checkpoint_digest TEXT NOT NULL,
    previous_epoch_id TEXT,
    active BOOLEAN NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_only_at TIMESTAMPTZ,
    retain_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, epoch_id),
    CHECK ((active AND read_only_at IS NULL) OR (NOT active AND read_only_at IS NOT NULL))
);

CREATE UNIQUE INDEX someday_sync_v2_one_active_epoch
    ON someday_sync_v2_epochs (user_id)
    WHERE active;

CREATE TABLE someday_sync_v2_checkpoint_chunks (
    user_id UUID NOT NULL REFERENCES someday_users (id) ON DELETE CASCADE,
    epoch_id TEXT NOT NULL,
    checkpoint_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    chunk_id TEXT NOT NULL,
    chunk_digest TEXT NOT NULL,
    object_count INTEGER NOT NULL CHECK (object_count BETWEEN 1 AND 64),
    plaintext_bytes INTEGER NOT NULL CHECK (plaintext_bytes BETWEEN 1 AND 8388608),
    encrypted_object_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, epoch_id, checkpoint_id, chunk_index),
    UNIQUE (user_id, epoch_id, chunk_id)
);

CREATE TABLE someday_sync_v2_checkpoint_manifests (
    user_id UUID NOT NULL REFERENCES someday_users (id) ON DELETE CASCADE,
    epoch_id TEXT NOT NULL,
    checkpoint_id TEXT NOT NULL,
    checkpoint_digest TEXT NOT NULL,
    chunk_count INTEGER NOT NULL CHECK (chunk_count > 0),
    total_object_count INTEGER NOT NULL CHECK (total_object_count > 0),
    chunk_refs_fingerprint TEXT NOT NULL,
    encrypted_object_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, epoch_id, checkpoint_id)
);

CREATE TABLE someday_sync_v2_objects (
    user_id UUID NOT NULL REFERENCES someday_users (id) ON DELETE CASCADE,
    epoch_id TEXT NOT NULL,
    object_id TEXT NOT NULL,
    object_type TEXT NOT NULL CHECK (object_type = 'workspace_entity_version_v2'),
    object_digest TEXT NOT NULL,
    mutation_id TEXT NOT NULL,
    first_writer_device_id UUID NOT NULL REFERENCES someday_devices (id) ON DELETE RESTRICT,
    cursor BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, epoch_id, object_id),
    UNIQUE (user_id, epoch_id, mutation_id),
    UNIQUE (user_id, epoch_id, cursor)
);

CREATE TABLE someday_sync_v2_object_replicas (
    user_id UUID NOT NULL,
    epoch_id TEXT NOT NULL,
    object_id TEXT NOT NULL,
    object_digest TEXT NOT NULL,
    mutation_id TEXT NOT NULL,
    writer_device_id UUID NOT NULL REFERENCES someday_devices (id) ON DELETE RESTRICT,
    ciphertext_digest TEXT NOT NULL,
    encrypted_object_json TEXT NOT NULL,
    repair_replica BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, epoch_id, object_id, writer_device_id),
    UNIQUE (user_id, epoch_id, object_id, ciphertext_digest),
    FOREIGN KEY (user_id, epoch_id, object_id)
        REFERENCES someday_sync_v2_objects (user_id, epoch_id, object_id) ON DELETE CASCADE
);

CREATE SEQUENCE someday_sync_v2_global_cursor;

CREATE TABLE someday_sync_v2_changes (
    cursor BIGINT PRIMARY KEY DEFAULT nextval('someday_sync_v2_global_cursor'),
    user_id UUID NOT NULL,
    epoch_id TEXT NOT NULL,
    object_id TEXT NOT NULL,
    object_digest TEXT NOT NULL,
    mutation_id TEXT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, epoch_id, object_id),
    UNIQUE (user_id, epoch_id, mutation_id),
    FOREIGN KEY (user_id, epoch_id, object_id)
        REFERENCES someday_sync_v2_objects (user_id, epoch_id, object_id) ON DELETE RESTRICT
);

CREATE INDEX someday_sync_v2_changes_epoch_cursor
    ON someday_sync_v2_changes (user_id, epoch_id, cursor);

CREATE TABLE someday_sync_v2_mutations (
    user_id UUID NOT NULL,
    epoch_id TEXT NOT NULL,
    mutation_id TEXT NOT NULL,
    object_id TEXT NOT NULL,
    object_digest TEXT NOT NULL,
    cursor BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, epoch_id, mutation_id),
    FOREIGN KEY (user_id, epoch_id, object_id)
        REFERENCES someday_sync_v2_objects (user_id, epoch_id, object_id) ON DELETE RESTRICT
);
