# Entity DAG V2 Subsystem

Status: frozen internal wire format used by System V3.

The `someday-system-v2` identifiers name Someday's independently versioned
entity-DAG encoding and merge engine. System V3 exposes that subsystem at:

```text
/sync/v3/workspaces/{workspaceId}/entities
```

## 1. Synchronized data

The synchronized entity types are:

- `note`;
- `notebook`;
- `workspace_preferences`.

A note contains notebook membership, title, Markdown body, creation time,
time-zone id, and the complete optional location value. A notebook contains
title, sort order, and creation time. Synchronized preferences contain theme,
preview-by-default, Markdown-toolbar visibility, and default notebook id.

Credentials, endpoints, sessions, device-local settings, workspace keys,
recovery material, and navigation state remain outside the entity graph. Image
bytes use the sibling V3 media plane. Notes refer to immutable images by
`someday-asset://` URI in Markdown.

## 2. Frozen wire identity

| Item | Value |
| --- | --- |
| Subsystem ID | `someday-system-v2` |
| Entity schema set | `workspace-entity-schema-set-v2` |
| Semantic protocol | `2` |
| Minimum writer protocol | `2` |
| Key set | `sync-key-set-v2` |
| Metadata privacy | `opaque` |
| Cipher suite | `xchacha20-poly1305-ietf` |

Entity and control plaintext use deterministic CBOR. Encrypted outer objects
use strict, bounded JSON. Decoders reject unknown or duplicate fields,
non-canonical encodings, invalid identifiers, unsupported contract values,
excessive bounds, and any mismatch between authenticated metadata and decoded
plaintext. Golden canonical and key-derivation vectors make a wire change
explicit.

`workspaceId` is supplied by the System V3 path, forms part of the server
storage scope, and is omitted from entity request bodies.

## 3. One local source of truth

Creating a workspace also creates one healthy local `PREPARING` generation.
Notes, notebooks, deletions, and synchronized preferences use the DAG-backed
repositories from the first offline mutation. Turning network synchronization
off pauses transport while local changes continue through the same repositories
and tables.

A local mutation commits atomically:

1. one immutable entity version and its parent edges;
2. the derived head set and explicit conflict state;
3. the typed product projection;
4. one durable pending transport mutation.

A pulled cursor unit's semantic effects, replay identity, outbox effects,
projection state, and cursor advancement are atomic. A bounded set of causally
ordered units may share one local transaction. Batching does not merge protocol
units, relax stream ordering, or advance a rejected unit's cursor. The DAG is
authoritative; projections are rebuildable views. Product and UI code write
through typed repositories rather than protocol tables or projection rows.

Portable structured export reads this same DAG product view. Import creates
normal DAG mutations in the selected writable local generation. Media bytes
are excluded from the current portable format.

## 4. Entity causality and conflicts

Each immutable version identifies one entity and contains a sorted, bounded
parent set; either typed content or a deletion; author and authored time;
generation and provenance; and authenticated payload/object digests. Parents
must exist in the same workspace generation, belong to the same entity, be
acyclic, and have lower generation numbers. Exact replay succeeds only when
canonical bytes match.

Current state is derived from ancestry, not wall-clock order. Deterministic
typed reconciliation:

- converges equivalent content and concurrent deletions;
- merges independent field edits only with an unambiguous common base;
- preserves delete/edit, same-field, incompatible-location, and ambiguous-base
  cases as explicit conflicts;
- requires a manual resolution to name the exact head set it resolves.

The server stores opaque ciphertext and transport metadata. It never decrypts
entities or classifies semantic conflicts.

## 5. Initial checkpoint and publication

One authenticated pointer selects the workspace's single generation. A fresh
installation keeps its locally prepared checkpoint and offline mutations. On
ordinary first connection:

- an empty remote workspace accepts that same generation by one
  compare-and-set; or
- an existing remote pointer is accepted only when the local draft has no
  semantic changes.

A normal sync never replaces or merges a contentful local workspace. Joining
another workspace is a separate Pair or Recover operation with explicit
destructive confirmation, described by the System V3 control plane. Stable
installation UUIDv4 is used as the local DAG writer and is claimed exactly by
device registration before first publication.

A checkpoint manifest references immutable bounded chunks containing the
complete entity graph needed to bootstrap another device, including live and
deleted versions, conflict branches, projections, and provenance. Every chunk
must exist before its manifest or pointer becomes visible. When selected
versions reference media, the System V3 publication boundary first proves or
uploads each immutable media object in the same account and workspace.

The current protocol supports one generation. Initial pointer CAS requires all
predecessor and retention fields to be absent. The descriptor retains nullable
fields for decoder compatibility, but clients and the server reject non-null
lineage.

## 6. Normal synchronization

A run performs these bounded operations:

1. load and authenticate the current workspace pointer;
2. bootstrap the current checkpoint when local state is semantically empty;
3. pull cursor units and apply causally eligible units atomically;
4. reconcile heads and materialize explicit conflicts;
5. prove referenced media durability for the exact versions being sent;
6. upload immutable entity objects idempotently;
7. conditionally advance the writer stream;
8. report actual pull, push, conflict, and blocked counts.

Order is authoritative within a writer stream only. Units from different
writers may arrive in any order. Missing-parent units remain deferred while
eligible units progress; a run that cannot make causal progress does not push
unsafe work or advance the blocked cursor.

The pull page is the dependency-scheduling window, not the SQLite transaction
boundary. The client selects bounded, causally continuous work from the complete
page and continues from each durable frontier. A rejected unit does not prevent
independently valid work from progressing, and exact per-unit failure evidence
is retained. An unexpected SQLite failure rolls the complete current local
transaction back for exact retry.

An immutable-object identifier may be replayed only with its exact encrypted
outer bytes. Authenticated malformed data, a different value at an existing
identity, a cursor gap, missing checkpoint data, or an incompatible pointer is
recorded as bounded durable failure evidence and leaves synchronization
`BLOCKED`. Recovery uses a valid operator backup; clients do not synthesize
replacement ciphertext.

## 7. Server storage contract

Entity storage is keyed by authenticated `userId`, canonical `workspaceId`,
and protocol identity. Every repository statement includes both account and
workspace predicates; PostgreSQL row-level security is defense in depth, not
the source of workspace isolation. The server provides only the primitives
required for the single generation:

- current pointer read and initial compare-and-set;
- immutable encrypted object put/get;
- bounded checkpoint manifest/chunk fetch and abandoned-draft cleanup;
- writer cursor append, frontier, and pull;
- status/capability reporting.

Every route is authenticated, device-bound, workspace-scoped, strict-JSON
decoded, and size bounded. Device/session revocation is checked before access.
There is no epoch-history, object-repair, remote-migration, rotation, or
retained-generation API.

## 8. Keys, recovery, and pairing

The workspace master key is generated on-device and stays in platform secure
storage. Subkeys bind purpose, generation identity, and the frozen key-set
version. Durable recovery and device pairing remain outside this frozen entity
graph. System V3 stores one opaque recovery-code-wrapped envelope per account
under [`workspace-recovery-protocol.md`](workspace-recovery-protocol.md).
Pairing transfers an invitation-local recovery package through the end-to-end
encrypted, single-use capability in
[`workspace-pairing-protocol.md`](workspace-pairing-protocol.md); that package
does not contain or rotate the user's durable recovery code.

An inviter needs an active published pointer. Joining requires explicit,
per-attempt confirmation that the current local workspace will be discarded
without merging. Without confirmation the client refuses before claiming the
invitation. With confirmation, Pair may replace a contentful, published,
blocked, or locked local workspace.

Pair holds the shared workspace-lifecycle lock. One database transaction
removes every local generation, DAG, projection, protocol row, and media row;
installs the authenticated workspace metadata; and binds the new authority.
The stable installation identity and authenticated session remain local. A
failure before commit preserves the old workspace. After commit, unreferenced
media files are removed on a best-effort basis. The old workspace and its
media remain on its server.

Master-key rotation and cryptographic device revocation are not currently
supported. Server device/session token revocation is supported. Future key
rotation requires a whole-workspace design that also migrates media encryption.

## 9. Required evidence

`scripts/sync-v3-reliability-gate` and `scripts/sync-v3-apple-gate` are the
host-specific product acceptance gates. Together they verify:

- frozen canonical encoding and crypto vectors;
- local and remote transaction rollback behavior;
- first-offline-edit, first publication, bootstrap, restart, and conflict
  convergence;
- explicit destructive pairing from contentful and unpublished workspaces;
- stable device identity and real multi-workspace isolation;
- media-before-entity ordering and immutable media replay;
- strict TLS, opaque-server, and route-scope boundaries;
- SQLDelight/Flyway checks, client platform compilation, PostgreSQL integration,
  and end-to-end self-hosted behavior.

The gates require fresh passing suites for each target and architecture-level
assertions. They do not pin individual test method names, so ordinary test
refactoring does not rewrite the acceptance criteria.
