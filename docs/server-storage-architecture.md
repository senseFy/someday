# Server Storage Architecture

Status: implemented pre-release architecture. Standalone filesystem and
external S3-compatible media storage use the same production server image.

## 1. Decision

The accepted architecture defines two self-hosted storage topologies:

| Topology | PostgreSQL | Media ciphertext | Application container | Intended use |
| --- | --- | --- | --- | --- |
| `standalone` | Bundled with a durable volume | Filesystem volume | Requires the mounted media path | Evaluation, home servers, and operators who deliberately keep all services local |
| `external` | Operator-selected external PostgreSQL | Private S3-compatible bucket | Holds no durable user data | Recommended production topology |

Both are first-class architecture targets. `external` is the production
recommendation because the Someday application can be destroyed, replaced, or
moved without moving its durable data. External services may be managed by a
third party or self-hosted by the operator on separate infrastructure;
Someday does not require a particular vendor.

The `local` and `production` server runtime modes remain security policies.
They are orthogonal to these storage topologies. For example, a standalone
installation exposed to users still runs with
`SOMEDAY_DEPLOYMENT_MODE=production`.

Someday integrates only with standard PostgreSQL and one S3-compatible object
contract. It does not gain a storage-provider plugin system or vendor-specific
adapters.

Both topologies use the same server image, HTTP API, synchronization protocol,
and client behavior. Only server-side resource configuration differs.

## 2. Persistence boundary

The durable state is divided deliberately:

| Owner | Durable contents |
| --- | --- |
| PostgreSQL | Accounts, devices, sessions, workspace registry, pairing state, encrypted entity DAG, cursors, and media object metadata |
| Media blob store | Immutable client-encrypted image objects only |
| Operator secret store | Stable JWT secret and storage/database credentials |
| Clients | Local-first workspace state and materialized images; not a complete server backup |

In the external topology, the application container owns only ephemeral
process state, temporary request data, logs, and the current in-memory rate
limiter. It must start on a fresh filesystem using only configuration and the
three external resources above. Flyway remains the only server database
migration path.

Persistence-stateless does not by itself mean horizontally scalable. The first
release remains a single application replica unless an operator supplies a
shared edge rate limiter and separately validates multi-replica behavior.

PostgreSQL and the media store together are one logical recovery unit. Client
encryption protects note and image contents from those services, but account,
workspace, object-size, and access-timing metadata remain visible to the
server and its storage operators.

## 3. Configuration contract

There is no `SOMEDAY_STORAGE_TOPOLOGY` switch. PostgreSQL is always selected by
its connection settings, and media has exactly one explicit backend selector:

```text
SOMEDAY_DB_URL
SOMEDAY_DB_USER
SOMEDAY_DB_PASSWORD
SOMEDAY_DB_MAX_POOL_SIZE=10
SOMEDAY_MEDIA_BACKEND=filesystem|s3
```

The process uses one shared bounded database pool. The maximum accepts 1–32
connections; operators should keep it within the external database's total
connection budget.

External PostgreSQL must expose a direct connection or a session-affine
pooler. Transaction-mode pooling is unsupported because RLS scope uses
session settings. Connections that cross an untrusted network must verify TLS;
loopback and operator-controlled isolated networks are the explicit exception.

Local development may default to `filesystem`. Production must require an
explicit `SOMEDAY_MEDIA_BACKEND` so an omitted setting never falls back to the
application container's writable layer.

The filesystem backend requires:

```text
SOMEDAY_MEDIA_BLOB_DIR=/absolute/app-owned/path
```

The S3-compatible backend requires:

```text
SOMEDAY_MEDIA_S3_BUCKET=<private dedicated bucket>
SOMEDAY_MEDIA_S3_REGION=<region>
```

It also accepts only these compatibility settings:

```text
SOMEDAY_MEDIA_S3_ENDPOINT=<optional endpoint URL>
SOMEDAY_MEDIA_S3_PATH_STYLE=false
```

Use TLS whenever the object-store connection crosses an untrusted network.
Loopback or isolated test infrastructure may use HTTP without weakening the
public client HTTPS requirement.

Credentials come from the standard AWS credential-provider chain. The current
distribution supports conventional `AWS_ACCESS_KEY_ID`,
`AWS_SECRET_ACCESS_KEY`, and optional `AWS_SESSION_TOKEN` environment
variables, shared configuration, and container or instance credentials.
Web-identity role assumption is not bundled. Someday-specific copies of these
secret variables are not added.

Configuration is fail-closed: settings for the unselected backend never affect
runtime behavior, while a missing or malformed setting for the selected
backend stops startup. The server does not probe several backends and does not
fall back after a storage error.

## 4. Media store contract

Both target backends share the same opaque `MediaBlobStore` boundary. The
logical object identity remains `(userId, workspaceId, mediaId)`. Physical
filesystem sharding is an implementation detail; the S3 adapter uses this
deterministic, server-private object key:

```text
media/v1/<user-id>/<workspace-id>/<media-id>.bin
```

An S3-compatible service is supported only if it provides:

- private single-object PUT, HEAD, and GET;
- strong read-after-write behavior;
- conditional create equivalent to `If-None-Match: *`;
- stable user metadata for the canonical ciphertext SHA-256;
- no bucket lifecycle rule that can remove live Someday objects.

AWS S3 returns `403`, rather than a distinguishable missing-object `404`, for
HEAD/GET when the caller lacks `ListBucket`. The runtime policy therefore also
grants `ListBucket` only when `s3:prefix` matches `media/v1/*`. The application
does not expose or invoke a bucket-list operation; this narrowly scoped
permission exists only to preserve fail-closed missing-versus-denied behavior.
`DeleteObject` remains denied.

Someday does not interpret ETag as a content digest. It compares the canonical
ciphertext length and SHA-256 metadata on HEAD and re-hashes bytes read by GET.
HEAD metadata is only a fast integrity signal: after a conditional-create
collision, the server must perform a bounded GET and hash the actual object
before accepting it as an exact replay or adopting an orphan. Missing,
different, or internally inconsistent metadata never permits overwrite. The
existing 4 MiB plaintext bound makes every first-release upload a single
request; multipart upload is unnecessary.

The bucket is never public. Clients continue to use authenticated Someday
media routes, and the server remains responsible for account/workspace scope,
quota, integrity checks, and error mapping. Object-store credentials, public
object URLs, and provider details never enter the sync protocol or Markdown.

## 5. Publication and failure semantics

PostgreSQL and S3 do not participate in a distributed transaction. Someday
uses one simple ordering rule instead:

```text
conditional immutable blob write -> PostgreSQL media metadata commit
```

The required invariants are:

1. An object is durable before PostgreSQL makes it visible.
2. An existing object is never overwritten. Exact length-and-digest replay
   succeeds only after hashing the actual stored bytes; a different value at
   the same identity is rejected.
3. If the database transaction fails after the object write, the unreferenced
   object remains a safe orphan. An exact retry reuses it and commits metadata.
4. PostgreSQL is the authoritative visible-object index. An orphan is neither
   readable through the API nor counted as published quota, although it still
   consumes object-store capacity.
5. If metadata exists but its blob is missing, the same authenticated client
   may reconstruct it only with an exact replay matching that metadata.

The application runtime does not invoke `DeleteObject`, bucket listing, a
compensation queue, two-phase commit, or an orphan state machine. Published
objects and safe orphans are append-only for the first release. Any future
garbage collection requires a separate authenticated reachability design and
must not be hidden inside upload failure handling.

## 6. Backup and recovery

For the recommended external topology:

- enable PostgreSQL point-in-time recovery and retain portable logical dumps;
- enable bucket versioning or equivalent retention and keep objects at least
  as long as the oldest retained database recovery point;
- back up the stable JWT secret and resource configuration separately;
- periodically restore PostgreSQL and the bucket into an isolated environment
  and run the operator media-integrity validator;
- retain an off-provider copy when recovery must survive provider or account
  loss.

A restored bucket may be a superset of the database: unreferenced immutable
objects are harmless. A recovery set is valid only when every PostgreSQL media
row has an object with the exact expected key, length, and SHA-256. Capture
both sides while writes are quiesced, or validate that invariant after an
online capture; restore-point timestamps alone do not prove completeness. The
validator enumerates authoritative PostgreSQL rows and performs bounded object
GETs, so it needs neither bucket listing nor a runtime repair state machine.

For standalone, a Docker volume is persistence but not backup. PostgreSQL and
the media directory require coordinated, off-host backups, together with the
same secret and restore testing. Copying only the application container or its
writable layer is never a backup strategy.

Clients are not treated as disaster-recovery replicas. Media is materialized
lazily and is not guaranteed to exist on every client, while the current
portable export intentionally omits image bytes and server authority state.

## 7. Deliberate exclusions

The first release does not add:

- PostgreSQL `BYTEA` media storage;
- WebDAV, provider-specific storage adapters, or a generic plugin registry;
- S3-mounted FUSE filesystems as a substitute for the S3 backend;
- presigned client upload/download, CDN URLs, or public buckets;
- multipart upload, video, or general attachments;
- multiple buckets, runtime failover, or online provider migration;
- distributed transactions, deletion queues, or automatic garbage collection.

These exclusions keep the storage boundary small: one relational protocol,
one object protocol, two explicit deployment topologies.

## 8. Implementation boundary

The implementation intentionally mirrors the small architecture:

- one explicit filesystem/S3 backend selector and no runtime fallback;
- conditional append-only media writes with bounded GET verification;
- one bounded PostgreSQL connection pool shared by the server repositories;
- real filesystem, PostgreSQL, and pinned S3-compatible contract evidence;
- one non-root production image with standalone and external Compose examples;
- one read-only operator validator that enumerates PostgreSQL media rows and
  hashes bounded blob reads without listing or mutating the media store.

The recovery tests accept an object-store superset and reject missing or
byte-divergent referenced objects. The production gate runs the complete media
journey against PostgreSQL and the pinned S3-compatible service. These checks
do not introduce a storage plugin registry, repair workflow, or background
orphan state machine.
