# Server Storage Architecture

Status: implemented. Standalone filesystem and
external S3-compatible media storage use the same production server image.

## 1. Decision

Someday supports two self-hosted storage topologies:

| Topology | PostgreSQL | Media ciphertext | Application container | Intended use |
| --- | --- | --- | --- | --- |
| `standalone` | Bundled with a durable volume | Filesystem volume | Requires the mounted media path | Evaluation, home servers, and operators keeping all services local |
| `external` | Operator-selected external PostgreSQL | Private S3-compatible bucket | Holds no durable user data | Recommended production topology |

Both are supported. `external` is the production
recommendation because the Someday application can be destroyed, replaced, or
moved without moving its durable data. External services may be managed by a
third party or self-hosted by the operator on separate infrastructure;
Someday does not require a particular vendor.

The `local` and `production` modes control server security independently of
storage. For example, a standalone installation exposed to users runs with
`SOMEDAY_DEPLOYMENT_MODE=production`.

Storage integrations use standard PostgreSQL and the S3-compatible object API.
Provider-specific settings stay in deployment configuration.

Both topologies use the same server image, HTTP API, synchronization protocol,
and client behavior. Only server-side resource configuration differs.

## 2. Persistence boundary

Durable state is divided between:

| Owner | Durable contents |
| --- | --- |
| PostgreSQL | Accounts, devices, sessions, workspace registry, pairing state, encrypted entity DAG, cursors, and media object metadata |
| Media blob store | Immutable client-encrypted image objects and a fixed non-secret startup probe |
| Operator secret store | Stable JWT secret and storage/database credentials |
| Clients | Local-first workspace state and materialized images; not a complete server backup |

In the external topology, the application container owns only ephemeral
process state, temporary request data, logs, and the current in-memory rate
limiter. It must start on a fresh filesystem using only configuration and the
three external resources above. Flyway manages server database migrations.

The current server runs as one application replica. External storage alone
does not make multiple replicas safe; that also requires shared rate limiting
and separate multi-replica validation.

PostgreSQL and the media store together are one logical recovery unit. Client
encryption protects note and image contents from those services, but account,
workspace, object-size, and access-timing metadata remain visible to the
server and its storage operators.

## 3. Configuration

Database connectivity and the media backend are configured with:

```text
SOMEDAY_DB_URL
SOMEDAY_DB_USER
SOMEDAY_DB_PASSWORD
SOMEDAY_DB_MAX_POOL_SIZE=10
SOMEDAY_DB_TLS_MODE=private|verify-full
SOMEDAY_MEDIA_BACKEND=filesystem|s3
```

The process uses one shared bounded database pool. The maximum accepts 1–32
connections; operators should keep it within the external database's total
connection budget.

PostgreSQL 17 must expose a direct connection. Transaction-mode pooling is
unsupported because RLS scope uses session settings. Production requires
`verify-full` for external databases and `private` only for loopback or an
operator-controlled private network. `verify-full` uses Java's standard trust
store unless the JDBC URL supplies an `sslrootcert` PEM path for a private CA.

Local development may default to `filesystem`. Production requires
`SOMEDAY_MEDIA_BACKEND`; a missing value stops startup instead of writing to
the application container.

The filesystem backend requires:

```text
SOMEDAY_MEDIA_BLOB_DIR=/absolute/app-owned/path
```

The S3-compatible backend requires:

```text
SOMEDAY_MEDIA_S3_BUCKET=<private dedicated bucket>
SOMEDAY_MEDIA_S3_REGION=<region>
```

Optional S3 compatibility settings are:

```text
SOMEDAY_MEDIA_S3_ENDPOINT=<optional endpoint URL>
SOMEDAY_MEDIA_S3_PATH_STYLE=false
```

Production endpoint overrides require HTTPS. Loopback integration fixtures may
use HTTP.

Credentials come from the S3 SDK credential-provider chain. The current
distribution supports `AWS_ACCESS_KEY_ID`,
`AWS_SECRET_ACCESS_KEY`, and optional `AWS_SESSION_TOKEN` environment
variables, shared configuration, and container or instance credentials.
Web-identity role assumption is not bundled.

Settings for the unselected backend have no effect. A missing or malformed
setting for the selected backend stops startup, and a storage error does not
switch the server to another backend.

## 4. Media store

Both target backends share the same opaque `MediaBlobStore` boundary. The
logical object identity remains `(userId, workspaceId, mediaId)`. Physical
filesystem sharding is an implementation detail; the S3 adapter uses this
deterministic, server-private object key:

```text
media/v1/<user-id>/<workspace-id>/<media-id>.bin
```

The bounded startup probe uses:

```text
media/v1/.someday-system/startup-probe-v1.bin
```

An S3-compatible service is supported only if it provides:

- private single-object PUT, HEAD, and GET;
- strong read-after-write behavior;
- conditional create equivalent to `If-None-Match: *`;
- stable user metadata for the canonical ciphertext SHA-256;
- no bucket lifecycle rule that can remove live Someday objects.

Missing HEAD/GET must be distinguishable from permission denial. When a
provider requires an additional bucket-level permission for that response,
grant only the smallest permission scoped to `media/v1/*`. The runtime itself
does not list or delete objects.

Someday does not interpret ETag as a content digest. It compares the canonical
ciphertext length and SHA-256 metadata on HEAD and re-hashes bytes read by GET.
HEAD metadata is only a fast integrity signal: after a conditional-create
collision, the server must perform a bounded GET and hash the actual object
before accepting it as an exact replay or adopting an orphan. Missing,
different, or internally inconsistent metadata never permits overwrite. The
4 MiB encoded-image bound makes every current upload a
single request; multipart upload is unnecessary.

Keep the bucket private. Clients use authenticated Someday media routes, and
the server remains responsible for account/workspace scope, quota, integrity
checks, and error mapping. Object-store credentials and provider details remain
server-side; Markdown contains only Someday asset IDs.

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

The application runtime does not invoke `DeleteObject` or bucket listing.
Published objects and safe orphans are currently append-only. Garbage
collection would require authenticated reachability data and is separate from
upload failure handling.

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
GETs without listing the bucket.

For standalone, a Docker volume is persistence but not backup. PostgreSQL and
the media directory require coordinated, off-host backups, together with the
same secret and restore testing. Copying the application container or its
writable layer does not back up these volumes.

Clients are not treated as disaster-recovery replicas. Media is materialized
lazily and is not guaranteed to exist on every client, while the current
portable export omits image bytes and server authority state.
The executable checklist is in `server-backup-and-recovery.md`.

## 7. Current limits

- Run one server replica.
- Choose one filesystem directory or one S3-compatible bucket for media.
- Media uses whole-image uploads; video, general attachments, and multipart
  upload are unsupported.
- Automatic provider migration, failover, and garbage collection are
  unsupported.

## 8. Verification

Integration tests cover PostgreSQL, filesystem storage, and a pinned
S3-compatible service. Recovery tests accept unreferenced objects but reject
missing or byte-divergent referenced objects. The production gate runs the
complete media journey against both storage backends and the non-root server
image.
