# Someday Server Sync (System V3)

Status: implemented.

System V3 synchronizes a workspace through one Someday Server, whether operated
by the user or a service provider. It has two data planes under one
authenticated account and workspace scope:

- an encrypted entity DAG for notes, notebooks, and synchronized preferences;
- one immutable encrypted media object for each supported image.

## 1. Architecture

The client owns all semantic state and cryptography. The server authenticates
accounts and devices, applies bounded immutable-object and compare-and-set
rules, and stores opaque ciphertext. It never receives recovery codes,
workspace keys, note content, image metadata, or image bytes in plaintext. It
may hold one opaque recovery-code-wrapped key envelope for each account.

Server persistence has two topologies. Standalone uses
PostgreSQL and a filesystem media volume; the recommended production topology
uses external PostgreSQL and private S3-compatible object storage so the
application container holds no durable user data. Both use the same server API
and image; clients do not select the storage backend. Storage details are in
`server-storage-architecture.md`.

The public API is rooted at:

```text
GET  /sync/v3/capabilities
...  /sync/v3/workspaces/{workspaceId}/entities
...  /sync/v3/workspaces/{workspaceId}/media
GET  /workspace/recovery-envelope
PUT  /workspace/recovery-envelope
```

`workspaceId` is a canonical `workspace-` prefix followed by 32 lowercase hex
characters. It is generated locally with the workspace key metadata and forms
a server storage and authorization scope.

The current clients expose one active local workspace. The protocol and server
schema scope records by `(account, workspaceId)`, and account quotas apply
across all workspaces owned by that account. Separately, an account may have one
current recovery envelope selecting one already initialized workspace. Older
workspace data may remain stored, but it is not another discoverable recovery
candidate.

The entity wire format uses the `someday-system-v2` descriptor for the
independently versioned DAG engine within System V3.

## 2. Identity and authority

Three identities have different jobs:

| Identity | Purpose |
| --- | --- |
| canonical endpoint + authenticated `userId` | remote account authority |
| `workspaceId` | workspace storage scope within the account |
| installation `deviceId` | durable DAG writer and authenticated device |

Every installation creates one high-entropy UUID and persists it before
network setup. Device registration claims that exact UUID. If a response is
lost, the same authenticated account may register the same non-revoked UUID
again: the server revokes that device's older sessions and issues fresh
credentials for the same identity. Another account cannot claim it, and a
revoked device is never resurrected. Registration never allocates a replacement
writer behind the client's back; the client rejects a response containing
another UUID.

After first publication, the client persists the exact account, workspace, and
writer binding. Entity publication, media access, setup reuse, pairing, and
recovery-envelope replacement all fail before network mutation if the current
session does not match it. Server session and device-token revocation are
supported. Master-key rotation and cryptographic device revocation are not
currently supported.

An expired or missing refresh session does not strand the workspace. Explicit
setup may authenticate again only at the bound endpoint, must recover the same
server `userId` before any device mutation, and then re-registers the exact
stable non-revoked writer UUID. A revoked device remains revoked.

Switching to another endpoint or account is a separate, explicitly confirmed
operation. An unbound local draft retains its contents and only forgets the
attempted connection. Once an account authority has been recorded, switching
clears the local workspace, session, media, and protocol history and creates a
fresh workspace id and master key. The previous server copy is retained; no
workspace merge or authority rebinding occurs.

## 3. One local source of truth

Creating a local workspace also creates its healthy local draft generation.
The entity DAG is the product source of truth from the first offline edit,
before login and before a server endpoint is configured. `SyncMode.Off` pauses
network work while local changes continue through the same repositories and
data model.

Product code reads and writes notes, notebooks, deletions, and synchronized
preferences through typed DAG repositories. A durable outbox records remote
work in the same local transaction as each mutation. UI and platform workers
do not write protocol or projection tables directly.

Authentication alone does not decrypt or choose a workspace. An unbound local
draft is not eligible for launch, foreground, or local-change automatic sync,
so signing in cannot silently publish it as a new remote workspace. The user
chooses one of three explicit operations:

1. Manual Sync publishes the current local workspace. An empty remote pointer
   accepts its prepared checkpoint with one compare-and-set and activates that
   same local generation.
2. Recover joins the account-current workspace with the user-held recovery code
   after destructive confirmation. The code and opaque envelope authenticate
   before any local data is removed.
3. Pair joins an existing workspace after destructive confirmation. The
   current local workspace is discarded without merging; the old server copy,
   if any, is not deleted.

Once a publication attempt, successful Recover, or successful Pair records an
account authority on the local generation, automatic sync may run and retry
normally.

## 4. Entity DAG

The synchronized entity types are:

- `note`
- `notebook`
- `workspace_preferences`

Versions are immutable and causally linked. Object identity, canonical
encoding, encryption, conflict materialization, the transactional outbox,
cursor progress, checkpoint bootstrap, and a durable blocking dead-letter
state remain core invariants.

The client may co-commit a bounded set of causally ordered cursor units in one
SQLite transaction. Unit identity, digest, ordering, cursor advancement, and
dead-letter evidence remain exact per-unit facts; transaction batching never
turns several protocol units into one synthetic unit.

The current protocol supports one generation and retains entity history.
Authenticated corrupt or incompatible input blocks synchronization with
bounded diagnostics; recovery restores server storage from backup instead of
creating replacement ciphertext on a client.

## 5. Image model

A note references an image in Markdown:

```text
![alt text](someday-asset://<64-lowercase-hex-asset-id>)
```

Markdown owns placement and alt text. The image asset is immutable; replacing
an image creates another asset ID. Binary data is never embedded in entity
versions.

The image surface supports:

- static JPEG, PNG, and WebP detected from bytes;
- at most 4 MiB of encoded original data;
- at most 12,000,000 decoded pixels;
- original bytes preserved in app-private storage;
- no SVG, animation, video, general files, or remote-URL fetching.

Each image is encrypted into one bounded object. At the 4 MiB limit, a retry
resends the whole object.

The encrypted envelope authenticates the media ID, workspace ID, media type,
optional safe filename, dimensions, plaintext size, and plaintext digest.
Key derivation, nonce derivation, and AAD are domain-separated and bind both
`workspaceId` and the opaque asset ID. The server sees only account/workspace
scope, opaque IDs, ciphertext length, and ciphertext digest.

PUT is immutable and idempotent: exact replay succeeds; another value at the
same key conflicts. A missing blob may be reconstructed only by an exact replay
matching its database identity. A divergent existing blob remains corrupt and
requires operator restoration; the application never overwrites it.

Media storage uses conditional immutable creation. Blob durability precedes
the PostgreSQL metadata commit; a database failure may leave an invisible
orphan that an exact replay reuses. The current server does not delete blobs or
switch storage providers at runtime.
A storage collision is an exact replay only after the server has bounded-read
and hashed the actual existing bytes; provider metadata alone is insufficient.

## 6. Cross-plane publication

An entity version must never become remote-visible before all image IDs it
references are remotely durable in the same account and workspace:

```text
image object -> entity outbox batch or initial checkpoint
```

The gate examines the exact immutable versions selected for publication, not
only the latest projection. For each referenced asset it either verifies the
account/workspace-scoped publication proof, uploads the verified local
original, or authenticates and materializes the existing remote object.
Unknown, missing, corrupt, or differently bound media aborts before the entity
write. Unreferenced local images neither upload automatically nor block sync.

The complete media-then-entity operation is single-flight per running client.
Active sync and media materialization share the workspace-lifecycle gate with
Pair, so work authenticated against an old workspace cannot write local state
after replacement. Concurrent manual or automatic requests do not read and
acknowledge the same durable outbox twice.

Receiving text does not wait for all images. A client applies the entity DAG,
then materializes referenced images lazily and verifies authenticated identity
before promoting bytes into local storage.

## 7. Workspace recovery

After first publication, a client holding the workspace master key may prepare
a user-held 128-bit recovery code and a portable wrapped-key envelope. The
portable form excludes the code, plaintext key, secure-storage alias, endpoint,
device identity, and session credentials. The server stores the opaque envelope
as the account's one current recovery pointer.

Setup and code replacement use a two-phase prepare-confirm-publish flow. The
client first generates and displays the candidate without remote mutation. Only
after the user enters the same code again does it PUT the candidate using the
observed server revision. Cancellation, failed confirmation, or process loss
before PUT leaves the previous envelope unchanged; a CAS conflict preserves the
concurrent winner. Once PUT has been sent, a lost response is ambiguous. The
client retries the exact candidate while it remains in memory; after restart it
GETs the account-current envelope and verifies it with a saved code. Failure of
the new code does not prove that the previous envelope survived because another
writer may have won. Replacing a recovery code rewraps the same master key; it
is not master-key rotation.

A fresh installation signs in, registers its stable device, and reads the
account-current envelope. Recovery requires explicit confirmation that its
current local workspace will be discarded without merging. The client verifies
the response digest and identities, authenticates and unwraps the master key
locally, then uses the same secure-key staging and atomic workspace replacement
as Pair. An incorrect code, malformed or tampered envelope, authority mismatch,
or local failure before commit preserves all previous local data. After commit,
ordinary initial sync downloads the entity graph and materializes media lazily.

Recovery state is not itself a data-plane authority. A fresh client that sees
an existing or unverifiable recovery pointer remains fail-closed until it
recovers or pairs. An already bound client with a verified local key continues
ordinary sync if the recovery route is unavailable or inconsistent; it keeps a
separate recovery-management warning and can retry that status check. A
verified missing route/envelope is treated as recovery not configured, including
against an older compatible server, while the ordinary workspace/session
preflight still decides whether sync may publish.

A recovery GET returning `404` is not a grant to publish a new workspace. The
server serializes recovery-envelope PUT and first-epoch CAS under one
account-scoped transaction lock. The CAS rereads the account-current recovery
pointer while holding that lock and rejects a competing workspace with
`workspace_recovery_required`, so a pointer published after the client's GET
cannot be bypassed. Existing authoritative workspaces and exact epoch replays
remain valid even when the account-current recovery pointer selects another
workspace. Both write paths pin PostgreSQL `READ COMMITTED` before their first
RLS statement so a `REPEATABLE READ` database default cannot preserve a stale
pre-lock snapshot. After this rejection, the client abandons its
never-authoritative PREPARING epoch and its provisional local authority before
refreshing recovery state; transient publication failures remain retryable.

The authenticated `GET` and `PUT /workspace/recovery-envelope` routes require a
non-revoked device `sync` token, are rate-limited, and return envelope state with
`Cache-Control: no-store`. The envelope is bounded to 64 KiB. Creation requires
an initialized workspace and a null expected revision; updates use revision
compare-and-set. Exact replay is idempotent and a different stale update
conflicts. The complete format, loss boundary, and required evidence are frozen
in [`workspace-recovery-protocol.md`](workspace-recovery-protocol.md).

Account authentication does not authorize the envelope cryptographically. A
party controlling the password can authenticate, register a device, replace or
withhold the opaque envelope, and deny future code-based recovery, but cannot
decrypt it without the recovery code. This is a current limitation.

## 8. Pairing

Pairing transfers the workspace key end to end through the single-use,
ten-minute capability defined in `workspace-pairing-protocol.md`. The server
stores only opaque invitation state and encrypted envelope bytes.

The pairing payload's internal `recoveryCode` is invitation-local wrapping
material. It is not the user-held durable recovery code and does not modify the
account-current recovery envelope.

An inviter must have an authenticated, published workspace authority. A
joining installation must explicitly authorize replacement on each attempt;
without that authorization it refuses before remote claim. After the invitation
package authenticates, one serialized database transaction discards all local
DAG, projection, protocol, and media records, installs the invited metadata,
and binds the new authority. Active, bound, blocked, locked, and contentful
workspaces are replaceable because the user has explicitly chosen deletion;
they are never merged.

## 9. Backup and recovery boundary

Synchronization is not backup.

Workspace-key recovery is also not server backup. A recovery code can unwrap a
key only when its matching account envelope and synchronized ciphertext still
exist. Users keep their codes outside Someday; operators must not collect them.

The current portable export and restore cover structured workspace data but
not image bytes. The export declares `includesMediaBytes=false`; Markdown
asset references may remain, but image bytes are not included and restored
references may therefore be unresolved. A complete portable media archive
would require a separate export format.

An operator backup of a self-hosted deployment is different: PostgreSQL and
the configured media blob store form one logical recovery unit. Either half
alone is incomplete. A standalone deployment requires coordinated PostgreSQL
and off-host filesystem backups. The recommended external topology requires
PostgreSQL recovery points and bucket versioning/retention; the restored bucket
may contain harmless orphans. Every restored PostgreSQL media record must
resolve to an object with the exact expected key, length, and actual-byte
digest; operators either quiesce writes for capture or run the operator
integrity validator afterward. The stable JWT secret is backed up separately.
The operational procedure is defined in `server-backup-and-recovery.md`.

Recovery envelopes live in PostgreSQL and follow its recovery point. A restored
older database may contain an older envelope revision that requires the code
current at that time; the latest code is not guaranteed to open it. An intact
device remains able to read its locally held key independently of that wrapper.

The current server does not garbage-collect published media. Local previews
are disposable caches; original bytes are retained unless a separately proven
remote copy exists.

## 10. Evolution

Supported extension points are:

- more local workspaces can be exposed using the existing `workspaceId` scope;
- target server media storage is limited to filesystem and S3-compatible
  adapters; it does not expose vendor-specific providers to clients;
- larger files would require a versioned media format separate from the current
  bounded-image format;
- complete media export and authenticated reachability-based deletion require
  explicit new formats;
- recovery-code replacement rewraps the same master key and does not reencrypt
  synchronized content;
- master-key rotation requires a workspace-wide migration that includes media
  re-encryption.

Protocol changes require canonical crypto vectors, immutable replay and
tamper tests, recovery prepare/confirm/CAS and atomic-replacement tests,
offline/restart tests, two-workspace isolation tests, media-before-entity
ordering tests, platform compilation, and real PostgreSQL integration.
Static checks enforce architectural boundaries; they must not substitute for
behavioral tests or hard-code a brittle list of test method names.

Test responsibilities across protocol, persistence, server, and real
self-hosted journey layers are defined in
`sync-system-v3-test-strategy.md`.
