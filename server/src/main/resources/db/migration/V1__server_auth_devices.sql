CREATE TABLE someday_users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    disabled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_someday_users_email ON someday_users (email);

CREATE TABLE someday_devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES someday_users (id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    platform TEXT NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_someday_devices_user_id ON someday_devices (user_id);

CREATE TABLE someday_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES someday_users (id) ON DELETE CASCADE,
    device_id UUID REFERENCES someday_devices (id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_someday_sessions_user_id ON someday_sessions (user_id);
CREATE INDEX idx_someday_sessions_device_id ON someday_sessions (device_id);

CREATE TABLE someday_refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES someday_sessions (id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_token_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_someday_refresh_tokens_session_id ON someday_refresh_tokens (session_id);
CREATE INDEX idx_someday_refresh_tokens_token_hash ON someday_refresh_tokens (token_hash);
