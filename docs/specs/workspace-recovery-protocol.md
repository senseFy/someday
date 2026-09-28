# Workspace Recovery Protocol

Status: implemented recovery-code and account-current-envelope contract.

Workspace recovery lets a signed-in installation recover an existing workspace
without another device. The client wraps the workspace master key with a
user-held 128-bit recovery code. Someday Server stores one opaque portable
envelope for the account; it never receives the code or the plaintext key.

Device pairing remains the preferred path when another trusted device is
available. Recovery is the independent disaster path when no such device
remains.

## 1. Security boundary

The workspace master key is 32 random bytes generated on a client. Note,
notebook, preference, and media encryption continues to derive from that key.
Account passwords authenticate a server account; they do not derive, wrap, or
decrypt the workspace key. Conversely, a recovery code does not sign in or
reset the account password.

The recovery code is a complete workspace secret when combined with its
portable envelope. It must be saved outside Someday and must never be sent to
the server, written to logs, included in diagnostics, or retained in ordinary
client settings. A server or database reader can see the account, selected
workspace id, key fingerprint, envelope digest and size, revision, timestamps,
and opaque envelope bytes. Those values do not reveal the workspace key.

The operating-system clipboard is outside Someday's secret-storage boundary.
Copying a recovery code therefore requires an explicit user action; the client
does not copy it automatically, read it back, persist it, or include it in
logs. Platforms may retain clipboard contents in previews, history, or
cross-device clipboard services. The client marks the clip as sensitive where
the platform supports that hint, but the user must still save the code in a
trusted location and clear the clipboard afterwards.

All recovery-envelope routes require an authenticated, non-revoked device
session with `sync` scope. An attacker who steals an active device sync or
refresh session can read or replace the account's opaque envelope. An attacker
who compromises the account password can authenticate, register a device, and
gain the same capability. Either path can cause denial of recovery, but does
not reveal the recovery code, workspace key, or content plaintext. This is a
current limitation; recovery-envelope replacement is not cryptographically
authorized by the workspace key.

If every device holding the key, the current recovery code, and every readable
plaintext backup are lost, neither a Someday operator nor a self-hosted
administrator can recover the synchronized ciphertext.

## 2. Recovery code and portable metadata

Generate exactly 16 random bytes with the platform cryptographic random source.
The user-visible form is `SOMEDAY-` followed by the uppercase hexadecimal bytes
in eight groups of four characters:

```text
SOMEDAY-0000-1111-2222-3333-4444-5555-6666-7777
```

Confirmation and recovery reject inputs longer than 96 characters, uppercase
the input, discard every character outside ASCII `A-Z` and `0-9`, then require
exactly `SOMEDAY` followed by 32 hexadecimal characters. The generated 128-bit
value, rather than the prefix or formatting, provides entropy.

The client derives a 32-byte wrapping key with Argon2id 1.3 using the stored
16-byte random salt and the protocol-v1 policy recorded in the metadata. It
wraps the workspace master key with XChaCha20-Poly1305. Associated data is:

```text
someday-workspace-recovery-v2|<workspaceId>
```

The authenticated key verifier uses the same workspace id and the existing
workspace-key verification contract. The portable metadata has this field set:

```json
{
  "format": "someday.workspace-recovery-metadata",
  "version": 1,
  "workspaceId": "<workspace id>",
  "createdAt": "<workspace creation instant>",
  "keyAlgorithm": "XCHACHA20-POLY1305-IETF",
  "recoveryKdf": "ARGON2ID13",
  "keyLengthBytes": 32,
  "keyFingerprint": "<32 lowercase hex characters>",
  "verifier": { "nonce": "<Base64>", "ciphertext": "<Base64>" },
  "recovery": {
    "salt": "<Base64>",
    "nonce": "<Base64>",
    "ciphertext": "<Base64>",
    "opsLimit": "2",
    "memLimit": 67108864,
    "algorithm": 2
  }
}
```

Protocol v1 freezes the Argon2id 1.3 profile at `opsLimit = 2`,
`memLimit = 67108864`, and `algorithm = 2`; clients do not derive these wire
values from a crypto library's evolving defaults. `opsLimit` is encoded as an
unsigned-decimal string, while `memLimit` and `algorithm` are JSON numbers. The
portable metadata contains no recovery code, plaintext master key,
secure-storage alias, device identity, account credential, endpoint, or
session token.

The client places that metadata string inside the account envelope:

```json
{
  "format": "someday.workspace-recovery",
  "protocolVersion": 1,
  "metadataJson": "<portable metadata JSON>",
  "workspaceId": "<workspace id>",
  "keyFingerprint": "<32 lowercase hex characters>"
}
```

The outer envelope is at most 64 KiB and the nested metadata is at most 48 KiB.
Its digest is unpadded Base64url SHA-256 of the exact UTF-8 envelope bytes.
Clients authenticate the nested wrapper and require the outer and inner
workspace identities and key fingerprints to agree. A workspace id is
`workspace-` plus 32 lowercase hexadecimal characters; a key fingerprint is 32
lowercase hexadecimal characters.

## 3. Server state and HTTP contract

The account exposes one current recovery pointer even when older workspaces are
retained in server storage:

```text
GET /workspace/recovery-envelope
PUT /workspace/recovery-envelope
```

There is no list or delete route. Both routes require a device-bound `sync`
token and apply account-and-device rate limits. Responses containing envelope
state use `Cache-Control: no-store`.

GET returns `404 not_found` when the account has no envelope. A successful
response has exactly these values:

```json
{
  "workspaceId": "<workspace id>",
  "keyFingerprint": "<key fingerprint>",
  "envelopeJson": "<opaque envelope JSON>",
  "envelopeDigest": "<43-character unpadded Base64url SHA-256>",
  "revision": 1,
  "updatedAtEpochMillis": 1000
}
```

A server version that predates this endpoint also answers GET with `404`, so a
client cannot distinguish it from an account with no envelope. Ordinary sync
remains compatible, but recovery setup PUT will fail until the server is
upgraded.

PUT accepts the same identity and opaque fields plus `expectedRevision`:

```json
{
  "workspaceId": "<workspace id>",
  "keyFingerprint": "<key fingerprint>",
  "envelopeJson": "<opaque envelope JSON>",
  "envelopeDigest": "<digest>",
  "expectedRevision": null
}
```

The selected workspace must already have an initialized entity epoch under the
authenticated account. Creation requires `expectedRevision=null` and stores
revision 1. Replacement requires the exact positive current revision and
increments it. Exact replay of every opaque identity field is idempotent even
when the request carries its original revision. A different absent, stale, or
concurrently replaced value returns `409 recovery_envelope_conflict` and leaves
the winner unchanged.

Successful creation returns `201`; replacement and exact replay return `200`.
Malformed identities, digests, revisions, or bounds return `400`, an oversized
request returns `413`, and selecting a workspace without an initialized entity
epoch returns `409 workspace_not_initialized`.

The server validates canonical public identifiers, the digest of the exact
envelope bytes, and the 64 KiB bound. It deliberately does not parse or decrypt
the opaque inner envelope. The database primary key is `user_id`, so a new
successful revision may select another already initialized workspace for that
account. Older workspace ciphertext may remain stored, but it is not another
discoverable recovery candidate.

## 4. Setup and recovery-code replacement

Recovery setup is available only after the local workspace has been published
and its active endpoint, account, workspace, and writer binding matches the
authenticated session.

Setup and replacement use two phases:

1. Prepare locally: generate a new recovery code, rewrap the same master key,
   fetch the current revision, and retain the candidate only in the pending UI
   session. No server mutation occurs.
2. Confirm and publish: show the code once, require the user to enter it again,
   revalidate the authority, then PUT with the observed revision. Success is
   reported only after the returned bytes and identity match the candidate.

While the pending code is visible, an explicit copy action writes the exact
user-visible value to the platform clipboard. Copy success does not confirm or
publish the candidate, and copy failure leaves the candidate visible without
changing server state. The UI reports either outcome without echoing the code.
Discarding or publishing the candidate removes the copy action, but it cannot
reliably erase copies retained by the operating system or clipboard history.

Explicit cancellation or process loss before PUT discards the
in-memory candidate and leaves the previous server envelope valid. A wrong
confirmation does not publish and leaves the candidate visible so the user can
retry. A CAS conflict clears the candidate, preserves the concurrent winner,
and requires a new code.

Once PUT has been sent, a transport failure or lost response does not prove
whether the server committed the candidate. While the process remains alive,
the client retains the exact candidate and may retry the identical request; the
server's exact-replay rule makes that retry idempotent. If the process exits,
the candidate is gone. On restart, GET returns the authoritative current
envelope. Recovery with the new code proves that candidate committed; failure
does not prove that the previous envelope survived because another writer may
have won. The user must keep the displayed new code until the current envelope
has been verified, and may need the previous code or an intact device if it was
not the winner.

Replacing a recovery code rewraps the same workspace master key. It does not
reencrypt notes or media and is not master-key rotation. After replacement, the
old code no longer opens the account's current server envelope.

## 5. Fresh-device recovery

A fresh installation signs in and registers its stable device before reading
recovery state. If GET returns an envelope and the installation does not hold
that workspace key, the client offers two explicit choices: recover with the
saved code, or pair from an existing trusted device. It must not publish its
fresh local workspace while that pointer identifies the existing workspace.

A preceding `404` is only an observation, not permission to create a workspace.
The server serializes recovery-envelope publication and every first-epoch CAS
with the same account transaction lock. Before committing a first epoch, it
rereads the account-current recovery pointer inside that transaction. If the
pointer selects another workspace, the CAS returns
`409 workspace_recovery_required` and creates no authoritative epoch. This
closes the race in which another device publishes a recovery envelope after the
fresh client observed `404`. The client refreshes recovery state and offers
Recover or Pair.

The first-epoch CAS acquires the account lock before its workspace lock. A
recovery-envelope PUT acquires the same account lock before locking the current
recovery row; no path reverses those orders. If a first epoch commits before an
account has a recovery pointer, that workspace is valid and a later
recovery-envelope PUT may select it. Existing authoritative workspaces and exact
epoch replays remain usable even when the account-current recovery pointer
selects a different workspace; recovery state never revokes an intact
workspace's data-plane authority.

Both write transactions pin PostgreSQL `READ COMMITTED` before executing RLS
scope SQL or waiting on the account lock. A deployment- or role-level
`REPEATABLE READ` default therefore cannot leave a waiter on the snapshot from
before the lock holder committed. After `workspace_recovery_required`, the
client atomically abandons only its never-authoritative PREPARING epoch and
clears that epoch's provisional local authority. The refreshed status then
offers Recover or Pair; unrelated transient publication failures retain their
prepared epoch for exact retry.

Recovery-control-plane availability and data-plane sync permission are separate
states. A client without a verified local workspace remains fail-closed when an
existing recovery envelope requires restoration or when that envelope cannot
be verified. The user can retry the status check explicitly; a user-requested
sync also retries a pending blocking check.

An already bound client with a locally verified workspace key does not lose
data-plane access merely because the recovery GET is rate-limited, temporarily
unavailable, malformed, or points at a different recovery package. It reports
recovery management as unavailable while ordinary sync continues under the
existing workspace, account, and writer-device authority guards. A verified
404 means recovery is not configured and never acts as an additional sync
gate; the normal data-plane preflight remains authoritative.

Recovery requires explicit confirmation that the installation's current local
workspace and unsynchronized changes will be discarded without merging. The
client then:

1. checks the response digest, bounds, format, workspace id, and key
   fingerprint;
2. derives the wrapping key from the user-entered code and authenticates the
   portable metadata, wrapped master key, and verifier;
3. stages the recovered key under a new platform secure-storage alias;
4. while holding the shared workspace-lifecycle boundary, removes every old
   local generation, DAG, projection, protocol, and media row in one database
   transaction, installs device-local metadata, and binds the recovered
   authority; and
5. removes the previous key alias and unreferenced media only after commit, then
   starts the ordinary initial sync.

An incorrect code, malformed or tampered envelope, authority mismatch,
secure-storage failure, or database failure before commit preserves the entire
previous local workspace. Failure of the first sync after commit is retryable;
it does not restore the discarded workspace.

## 6. Relationship to device pairing

Pairing and recovery share authenticated workspace import and atomic local
replacement, but their secrets and remote state are independent.

The `recoveryCode` field frozen inside the pairing invitation payload is
invitation-local wrapping material carried only inside that invitation's
end-to-end encrypted envelope. It is not the user-held workspace recovery code
described here. Pairing does not read, publish, replace, or rotate the account's
current recovery envelope.

## 7. Backup and historical envelopes

The recovery envelope is part of PostgreSQL backup state. A usable server
recovery still requires PostgreSQL and media to be restored as one logical
unit. The recovery code does not recreate missing entity or media ciphertext.

A historical database backup may contain an older envelope revision and
therefore require the recovery code that was current at that recovery point.
The newest code is not guaranteed to open an older restored envelope, and the
old code is not guaranteed to open current server state. Keep an intact device
when validating historical recovery points, or retain the corresponding test
code for a non-production recovery account. Operators must not collect real
users' recovery codes.

## 8. Required evidence

Release evidence must cover:

- 128-bit generation, user-input normalization, portable metadata without
  device aliases or plaintext secrets, KDF/AEAD authentication, and redaction;
- account scope, device-bound authentication, rate limits, size and digest
  validation, `no-store`, missing state, exact replay, CAS conflict, and one
  current pointer per account;
- the shared account lock between first-epoch CAS and recovery publication,
  including stale-`404` rejection with `workspace_recovery_required`, no
  competing authoritative epoch, and continued exact replay for an existing
  workspace;
- forced `READ COMMITTED` visibility when test connections begin at
  `REPEATABLE READ`, in both recovery-wins and epoch-wins lock orderings, plus
  abandonment of the rejected never-authoritative local epoch before recovery
  status refresh;
- prepare-copy-confirm-publish ordering, no automatic copy, exact-value copy
  without logging or publication, safe copy-failure feedback, pre-PUT
  cancellation and process-loss preservation, post-PUT lost-response
  ambiguity, exact replay, restart GET and code verification, and concurrent
  replacement;
- wrong-code and tampered-envelope refusal before local deletion, atomic
  replacement rollback, post-commit cleanup, and first-sync retry;
- a fresh installation recovering non-empty text and media without another
  device, followed by ordinary convergence; and
- PostgreSQL backup/restore retaining the envelope revision used by the
  read-only recovery check.
