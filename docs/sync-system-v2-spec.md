# Someday System V2 Sync Contract

Status: implementation contract for the open-source codebase.

System V2 is the only multi-device sync protocol implemented by Someday.
Clients and the self-hosted server exchange encrypted epoch, checkpoint, and
workspace-DAG objects. There is no alternate protocol, dual-write mode, or
transport bridge.

## 1. Product boundary

The synchronized workspace contains exactly three entity types:

- `note`
- `notebook`
- `workspace_preferences`

A note version contains notebook membership, title, Markdown body, creation
time, time-zone id, and the complete optional location value. A notebook
version contains title, sort order, and creation time. Workspace preferences
contain only:

- theme
- preview-by-default
- Markdown-toolbar visibility
- default notebook id

Credentials, endpoints, device-local notification settings, session state,
workspace keys, recovery material, and UI navigation state never enter the
synchronized workspace.

Attachments and media are outside this schema set. Adding an entity type or
field is a reviewed schema-set change.

## 2. Wire identity

The fixed contract identifiers are:

| Item | Value |
| --- | --- |
| Contract | `someday-system-v2` |
| Entity schema set | `workspace-entity-schema-set-v2` |
| Semantic protocol | `2` |
| Minimum writer protocol | `2` |
| Epoch key set | `sync-key-set-v2` |
| Metadata privacy | `opaque` |
| Cipher suite | `xchacha20-poly1305-ietf` |

Entity and control plaintext use deterministic CBOR. The encrypted outer
envelope uses strict bounded JSON. Decoders reject:

- unknown fields, duplicate keys, invalid enum values, and non-canonical CBOR;
- unsupported contract, schema-set, key-set, algorithm, or cipher values;
- invalid identifiers, malformed digests, excessive nesting, and oversized
  plaintext or ciphertext;
- a mismatch between authenticated metadata and decoded plaintext.

The canonical corpus and epoch-key derivation are protected by cross-platform
golden tests. Changing either requires an intentional wire-contract update.

## 3. Workspace entity DAG

Each immutable `WorkspaceEntityVersionV2` identifies one entity and contains:

- its epoch id and immutable version id;
- a sorted, bounded parent-version set;
- `content` or `deletion`, never both;
- the complete typed payload for content versions;
- author, authored time, generation, payload digest, object digest, and merge
  algorithm;
- optional source-import or checkpoint provenance.

Parents must exist, belong to the same entity and epoch, be acyclic, and have a
lower generation. Replaying the same immutable id is accepted only when the
canonical bytes are identical.

The current state of an entity is derived from DAG ancestry, not timestamps.
Concurrent heads are reconciled using deterministic typed rules:

- equivalent content converges;
- independent field edits merge when every changed field has a usable common
  base;
- concurrent deletions converge;
- delete/edit, same-field edits, ambiguous bases, and incompatible locations
  remain explicit conflicts;
- manual resolution names the exact head set it resolves.

Conflict records and product projections are rebuildable from immutable DAG
state. A projection is never a second authority.

## 4. Local transactions

A local product mutation atomically persists:

1. the immutable entity version and its parent edges;
2. the new head set;
3. conflict lifecycle changes;
4. the typed product projection;
5. one durable pending transport mutation.

A remote cursor unit atomically persists the same semantic effects plus replay
identity and cursor advancement. A failure at any boundary rolls back the
entire unit. Missing parents block the unit without advancing its cursor.

When an epoch is authoritative, Notes and synchronized-preference reads and
writes are routed through the System V2 repositories. Export and import use the
same DAG-backed product view.

## 5. Epochs and checkpoints

An authenticated epoch pointer selects one `SyncEpochDescriptorV2`. The
descriptor binds:

- contract, schema set, protocol and key-set versions;
- remote profile and opaque-metadata policy;
- checkpoint id and authenticated checkpoint digest;
- creator, creation time, and supported offline window;
- the preceding pointer digest and captured stream frontiers when rolling to a
  successor epoch.

A checkpoint is a complete workspace snapshot. Its manifest references
immutable, bounded chunks. Chunks include every live or deleted entity,
retained conflict branch, projection warning, and required provenance. The
manifest is published only after every referenced immutable chunk exists.

The first epoch is created from a full local product snapshot. Invalid or
oversized state fails before any pointer is published. A follower that already
has the shared workspace key bootstraps the current checkpoint, then pulls
incremental units before pushing local work.

The local lifecycle is `preparing`, `active`, `blocked`, or `read_only`.
Replacing a workspace key is refused while any key-bound lifecycle exists.

## 6. Coordinator ordering and failure behavior

A normal run:

1. loads and authenticates the current pointer;
2. verifies pointer ancestry and local authority binding;
3. bootstraps a missing local epoch from the complete checkpoint;
4. pulls bounded cursor units and applies them atomically;
5. reconciles DAG heads and records explicit conflicts;
6. uploads immutable objects idempotently;
7. conditionally advances the writer stream;
8. records real pull, push, conflict, and repair counts.

Authentication failures, rollback evidence, missing referenced objects,
immutable-id byte mismatches, cursor gaps, and persistent replica corruption
block the run before unsafe upload or cursor advancement.

Successful manual sync always refreshes the Notes product view, including a
zero-delta bootstrap. This is required for pair, join, sync, and immediately
view the leader workspace.

## 7. WebDAV profile

The WebDAV profile treats the server as a weak conditional object store. Its
application-owned tree is rooted at `log-v2/`:

```text
log-v2/
  control/
    epoch-pointer.enc
    pointer-history/<epoch-id>.enc
  epochs/<epoch-id>/
    checkpoints/...
    writers/<device-id>/...
    repairs/...
```

Required operations are `MKCOL`, depth-one `PROPFIND`, `GET`, and conditional
`PUT`. Immutable creation uses `If-None-Match: *`; mutable control objects use
an exact `If-Match` ETag. Lost responses are resolved by reading and comparing
the exact stored bytes.

WebDAV workspace reset is unavailable. Neither the runner nor the UI may issue
a recursive or tree DELETE. A reset can return only after a future
DAG-preserving checkpoint-and-pointer transition is implemented.

Pairing invitations live outside `log-v2/` under
`workspace-pairing/1/<invite-id>.json.enc`. Creation is append-only and claim
or cancellation conditionally replaces the envelope with an authenticated
tombstone. Pairing never sends WebDAV `DELETE`. The complete wire and state
contract is [`workspace-pairing-protocol.md`](workspace-pairing-protocol.md).

## 8. Self-hosted profile

The self-hosted service stores opaque encrypted bytes and protocol metadata.
It must not decrypt workspace content or classify semantic conflicts.

Authenticated routes provide:

- current pointer read and compare-and-swap;
- immutable object put/get;
- bounded checkpoint fetch;
- writer cursor append/pull;
- exact repair replicas;
- device-bound workspace-pairing create/claim/complete/cancel transitions.

Every request is size bounded and strict-JSON decoded. Device revocation takes
effect before access to V2 workspace routes. Access tokens used by sync follow
one serialized refresh path shared by sync and pairing.

## 9. Workspace keys and pairing

The workspace master key is generated on device and stored only in platform
secure storage. Epoch convergence and object-digest keys are derived from the
master key, epoch id, purpose, and `sync-key-set-v2`. Content encryption uses a
random 24-byte XChaCha20-Poly1305 nonce and authenticated outer metadata.

Pairing transfers the workspace key inside an end-to-end encrypted,
single-use invitation addressed by a 128-bit random capability. Its
28-character Crockford Base32 token contains a checksum and is normally
transferred as a QR payload. Independent HKDF-SHA-256 domains derive the
opaque invitation id, envelope key, and remote-state authentication key.
WebDAV and the self-hosted service store ciphertext only.

Joining authenticates the exact remote authority, expiry, envelope, workspace
id, and key fingerprint before committing a staged secure-storage key.
Workspace adoption and first-epoch activation share one serialization lock.
A failed join cannot partially replace the current workspace, and a device
with any key-bound local lifecycle is refused. The frozen wire contract and
threat model are in
[`workspace-pairing-protocol.md`](workspace-pairing-protocol.md).

## 10. Remote migration, key rotation, and retention

Moving between supported remotes creates one successor epoch from the current
V2 DAG and publishes a complete checkpoint on the target. There is no
dual-write interval. Pointer ancestry binds the successor to the exact prior
pointer and captured stream frontiers.

Master-key rotation stages recovery material out of band, publishes one
authenticated successor epoch, and retains the exact prior epoch key for its
retention window. Recovery is explicit and verifies the local DAG before
archiving a blocked epoch.

The supported offline window is 180 days. A prior epoch can be collected only
after its retention horizon and only when no pending mutation, import, repair,
or required checkpoint reference pins it.

## 11. Activation and release safety

`systemV2ActivationEnabled` is a required composition argument; it has no
default. Shipping activation is:

```text
SOMEDAY_SYSTEM_V2_RELEASE_ENABLED ||
SOMEDAY_SYSTEM_V2_DEVELOPMENT_ENABLED
```

Both Gradle properties default to `false`. macOS packaging, iOS release
scripts, and the release-framework Make target explicitly force both values to
`false`. Local activation must be deliberate.

When activation is disabled and no authority exists, sync fails closed without
creating an epoch. Existing authority can still be read and synchronized.

## 12. Required evidence

`scripts/sync-v2-reliability-gate` is the executable acceptance gate. It must:

- run the no-retired-surface architecture/privacy scan;
- verify SQLDelight migrations and run data, domain, sync, UI, and server JVM
  suites;
- run Android, iOS simulator, and Desktop compile/test coverage;
- run the self-hosted PostgreSQL integration suite;
- run the real WebDAV and self-hosted whole-product corpus;
- verify high-entropy pairing derivation, strict encrypted envelopes,
  one-use transport transitions, local adoption guards, and secret redaction;
- reject skipped, failed, or stale required evidence;
- run `git diff --check`.

`scripts/sync-v2-test-evidence.tsv` binds each contract area to at least one
current JUnit test or architecture assertion. The gate validates every row
against results produced in that same run.
