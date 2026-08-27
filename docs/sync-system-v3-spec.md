# Someday Self-Hosted System V3

Status: pre-release implementation contract.

System V3 is Someday's only product-facing synchronization architecture. It
has one remote type, `self-hosted`, and two data planes under one authenticated
account and workspace scope:

- an encrypted entity DAG for notes, notebooks, and synchronized preferences;
- one immutable encrypted media object for each supported image.

WebDAV, provider switching, provider migration, and remote fallbacks are not
part of the product.

## 1. Architecture boundary

The client owns all semantic state and cryptography. The server authenticates
accounts and devices, applies bounded immutable-object and compare-and-set
rules, and stores opaque ciphertext. It never receives workspace keys, note
content, image metadata, or image bytes in plaintext.

The accepted server persistence target defines two topologies. Standalone uses
PostgreSQL and a filesystem media volume; the recommended production topology
uses external PostgreSQL and private S3-compatible object storage so the
application container holds no durable user data. These are deployment choices
behind the same server API, never client-selectable sync providers. Both are
implemented by the same server image. The normative storage decision is in
`server-storage-architecture.md`.

The public API is rooted at:

```text
GET  /sync/v3/capabilities
...  /sync/v3/workspaces/{workspaceId}/entities
...  /sync/v3/workspaces/{workspaceId}/media
```

`workspaceId` is a canonical `workspace-` prefix followed by 32 lowercase hex
characters. It is generated locally with the workspace key metadata. It is a
real server storage and authorization scope, not an epoch identifier or a
reserved placeholder.

The current clients expose one active local workspace. The protocol and server
schema use `(account, workspaceId)` throughout so adding a workspace selector
later does not require another wire or storage migration. Account quotas still
apply across all workspaces owned by that account.

The entity wire format retains the frozen `someday-system-v2` identifiers.
Those identifiers describe the independently versioned DAG engine; they are
not a second product protocol or a user-selectable mode.

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
writer binding. Entity publication, media access, setup reuse, and pairing all
fail before network mutation if the current session does not match it. Server
session/device-token revocation remains supported. Master-key rotation and
cryptographic device revocation are deliberately outside the first release.

An expired or missing refresh session does not strand the workspace. Explicit
setup may authenticate again only at the bound endpoint, must recover the same
server `userId` before any device mutation, and then re-registers the exact
stable non-revoked writer UUID. A revoked device remains revoked.

## 3. One local source of truth

Creating a local workspace also creates its healthy local draft generation.
The entity DAG is the product source of truth from the first offline edit,
before login and before a server endpoint is configured. `SyncMode.Off` pauses
network work only; it does not switch repositories or data models.

Product code reads and writes notes, notebooks, deletions, and synchronized
preferences through typed DAG repositories. A durable outbox records remote
work in the same local transaction as each mutation. UI and platform workers
must not write projection or compatibility tables directly.

The initial server connection has two valid outcomes:

1. An empty remote workspace accepts the prepared checkpoint with one
   compare-and-set and activates that same local generation.
2. An existing remote workspace may replace a semantically empty local draft.
   A non-empty local workspace is refused and is never silently merged.

The same adoption rule applies to pairing. Semantic content includes notes,
notebooks, deletion history, and local images. This keeps accidental
cross-workspace merging out of the synchronization engine.

## 4. Entity DAG

The closed entity set is:

- `note`
- `notebook`
- `workspace_preferences`

Versions are immutable and causally linked. Object identity, canonical
encoding, encryption, conflict materialization, the transactional outbox,
cursor progress, checkpoint bootstrap, and a durable fail-closed dead-letter
state remain core invariants.

The first release has one generation for a workspace. It does not expose
rollover, prior-generation retention, repair replicas, quarantine workflows,
remote migration, or user-triggered protocol recovery. Authenticated corrupt
or incompatible input blocks synchronization with bounded diagnostics; an
operator restores server storage from backup rather than asking clients to
invent replacement ciphertext. The server does not prune entity history or
signal an offline-window rebootstrap in this release.

## 5. Image model

A note references an image in Markdown:

```text
![alt text](someday-asset://<64-lowercase-hex-asset-id>)
```

Markdown owns placement and alt text. The image asset is immutable; replacing
an image creates another asset ID. Binary data is never embedded in entity
versions.

The initial image surface is intentionally closed:

- static JPEG, PNG, and WebP detected from bytes;
- at most 4 MiB of encoded original data;
- at most 12,000,000 decoded pixels;
- original bytes preserved in app-private storage;
- no SVG, animation, video, general files, or remote-URL fetching.

Each image is encrypted into one bounded object. There are no media chunks,
manifests, upload drafts, reservations, resumable-upload journals, or media
repair state machines. A whole-object retry is acceptable at the 4 MiB bound
and is substantially easier to reason about.

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
orphan that an exact replay reuses. The first release does not require runtime
blob deletion, provider failover, or a distributed compensation state machine.
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
Concurrent manual or automatic requests do not read and acknowledge the same
durable outbox twice.

Receiving text does not wait for all images. A client applies the entity DAG,
then materializes referenced images lazily and verifies authenticated identity
before promoting bytes into local storage.

## 7. Pairing

Pairing transfers the workspace key end to end through the single-use,
ten-minute capability defined in `workspace-pairing-protocol.md`. The server
stores only opaque invitation state and encrypted envelope bytes.

An inviter must have an authenticated, published workspace authority. A fresh
joining installation may discard its semantically empty default workspace
only after authenticating the invitation package. The adoption policy is
checked before remote claim and again inside the serialized local replacement
transaction. Non-empty local state is refused.

## 8. Backup and recovery boundary

Synchronization is not backup.

For this release, the user-facing portable export and restore cover structured
workspace data only. The export declares `includesMediaBytes=false`; Markdown
asset references may remain, but image bytes are not included and restored
references may therefore be unresolved. A complete portable media archive is
a separate future feature, not an implicit extension of JSON export.

An operator backup of a self-hosted deployment is different: PostgreSQL and
the configured media blob store form one logical recovery unit. Either half
alone is incomplete. A standalone deployment requires coordinated PostgreSQL
and off-host filesystem backups. The recommended external topology requires
PostgreSQL recovery points and bucket versioning/retention; the restored bucket
may contain harmless orphans. Every restored PostgreSQL media record must
resolve to an object with the exact expected key, length, and actual-byte
digest; operators either quiesce writes for capture or run the operator
integrity validator afterward. The stable JWT secret is backed up separately.

The initial server does not garbage-collect published media. Local previews
are disposable caches; original bytes are retained unless a separately proven
remote copy exists.

## 9. Evolution rules

The architecture leaves narrow seams, not generic plugin systems:

- more local workspaces can be exposed using the existing `workspaceId` scope;
- target server media storage is limited to filesystem and S3-compatible
  adapters; it does not expose vendor-specific providers to clients;
- a larger-file protocol may later add resumable objects under a new media
  contract without changing the bounded-image contract;
- complete media export and authenticated reachability-based deletion require
  explicit new formats;
- master-key rotation requires a designed media re-encryption migration and is
  not approximated by a hidden entity-only operation.

Protocol changes require canonical crypto vectors, immutable replay and
tamper tests, offline/restart tests, two-workspace isolation tests, media-before-
entity ordering tests, platform compilation, and real PostgreSQL integration.
Static checks enforce architectural boundaries; they must not substitute for
behavioral tests or hard-code a brittle list of test method names.

The ownership of those checks across protocol, persistence, server-contract,
and real self-hosted journey layers is defined in
`sync-system-v3-test-strategy.md`.
