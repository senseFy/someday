CREATE TABLE workspace_pairing_invites (
    user_id UUID NOT NULL REFERENCES someday_users(id) ON DELETE CASCADE,
    invite_id TEXT NOT NULL,
    creator_device_id UUID NOT NULL REFERENCES someday_devices(id) ON DELETE CASCADE,
    envelope_json TEXT,
    envelope_digest TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('available', 'claimed', 'completed', 'cancelled')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claim_id TEXT,
    claim_device_id UUID REFERENCES someday_devices(id) ON DELETE RESTRICT,
    claimed_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, invite_id),
    CHECK (
        (state = 'available' AND envelope_json IS NOT NULL AND claim_id IS NULL AND claim_device_id IS NULL AND claimed_at IS NULL)
        OR
        (state = 'claimed' AND envelope_json IS NOT NULL AND claim_id IS NOT NULL AND claim_device_id IS NOT NULL AND claimed_at IS NOT NULL)
        OR
        (state = 'completed' AND envelope_json IS NULL AND claim_id IS NOT NULL AND claim_device_id IS NOT NULL AND claimed_at IS NOT NULL)
        OR
        (state = 'cancelled' AND envelope_json IS NULL AND claim_id IS NULL AND claim_device_id IS NULL AND claimed_at IS NULL)
    )
);

CREATE INDEX workspace_pairing_invites_user_expires_idx
    ON workspace_pairing_invites (user_id, expires_at);

CREATE INDEX workspace_pairing_invites_expires_idx
    ON workspace_pairing_invites (expires_at);
