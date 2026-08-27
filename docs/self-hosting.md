# Self-hosting Someday

The Ktor service provides authenticated device management, opaque System V3
entity and media storage, pairing transport, and an operator dashboard. Note
content, image bytes, and workspace keys remain client-encrypted, but account
metadata, device metadata, availability, and deletion controls are still
security-sensitive.

This service is Someday's only remote synchronization authority. Clients do
not select a storage provider or fall back to another transport. The public
`someday-system-v3` capability composes the frozen
`someday-system-v2` entity-DAG wire contract at
`/sync/v3/workspaces/{workspaceId}/entities` with the
`someday-system-v3-media-v1` media contract at
`/sync/v3/workspaces/{workspaceId}/media`; both and their
cross-plane publication ordering are required, so the V3 route prefix is not a
compatibility facade.

The initial media surface accepts one immutable client-encrypted object per
static JPEG, PNG, or WebP image. Original encoded bytes are limited to 4 MiB
and decoded dimensions to 12,000,000 pixels. SVG, animation, video, and general
attachments are not accepted.

The accepted persistence architecture provides both a standalone topology and
an external-resource topology. External PostgreSQL plus private S3-compatible
object storage is the recommended production topology; see
`server-storage-architecture.md`. Both topologies are implemented by the same
server image and differ only in resource configuration.

`compose.yaml` is a **local development dependency stack**, not a production
deployment. Its PostgreSQL port binds to loopback and its credentials are
intentionally well-known.

## Runtime security modes

The server has two explicit modes:

- `local` is the default. It binds to `127.0.0.1`, allows registration, accepts
  an HTTP admin origin, and generates a new random JWT secret when one is not
  provided. Restarting without a stable secret invalidates existing sessions.
- `production` fails startup unless the database connection, a 32-byte-or-longer
  JWT secret, and an HTTPS public base URL are explicit. Registration and proxy
  header trust both default to off. Admin cookies are always `Secure`.

Production mode is selected with:

```bash
export SOMEDAY_DEPLOYMENT_MODE=production
```

## Storage topologies

Storage topology is independent of runtime security mode:

| Topology | Database | Media | Application state | Recommendation |
| --- | --- | --- | --- | --- |
| `standalone` | PostgreSQL deployed with Someday | App-owned filesystem volume | Media volume is durable | Simple, fully local Docker target |
| `external` | External PostgreSQL | Private S3-compatible bucket | Container is disposable | Recommended production topology |

An external service may be managed or self-hosted on separate operator-owned
infrastructure. Someday targets standard PostgreSQL and S3-compatible
contracts, not particular vendors. The server never exposes object-store URLs
or credentials to clients.

The configuration has no topology flag. Database connection settings
select PostgreSQL, while `SOMEDAY_MEDIA_BACKEND=filesystem|s3` selects exactly
one media implementation. Production requires that selector explicitly.
The accepted variable contract and S3 capability requirements are defined in
`server-storage-architecture.md`.

## Production storage configuration

The recommended external topology uses:

```bash
export SOMEDAY_PUBLIC_BASE_URL=https://notes.example.com
export SOMEDAY_DB_URL=jdbc:postgresql://database.internal:5432/someday
export SOMEDAY_DB_USER=someday_app
export SOMEDAY_DB_PASSWORD='<random database password>'
export SOMEDAY_JWT_SECRET="$(openssl rand -base64 48)"
export SOMEDAY_MEDIA_BACKEND=s3
export SOMEDAY_MEDIA_S3_BUCKET=someday-private-media
export SOMEDAY_MEDIA_S3_REGION=us-east-1
```

Use a direct PostgreSQL endpoint or a session-affine pooler. Transaction-mode
poolers are not supported because Someday's defense-in-depth RLS scope is bound
to the database session; the server already owns a bounded connection pool.
When PostgreSQL is reached across an untrusted network, use a JDBC URL that
verifies TLS, such as `?sslmode=verify-full`, together with the provider's CA
requirements. Plaintext connections are appropriate only for loopback or an
operator-controlled isolated network.

Set `SOMEDAY_MEDIA_S3_ENDPOINT` and
`SOMEDAY_MEDIA_S3_PATH_STYLE=true` only when the selected S3-compatible service
requires them. Object-store credentials use the standard AWS provider chain;
use container or instance credentials when the runtime exposes them, or
provide `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and optional
`AWS_SESSION_TOKEN`. Web-identity role assumption is not part of the current
server distribution. The runtime identity needs `GetObject` and conditional
`PutObject` under `media/v1/`; it does not need object deletion. On AWS S3,
also grant `ListBucket` restricted by the `media/v1/*` prefix condition so a
missing HEAD/GET is reported as `404` rather than an indistinguishable
permission `403`. The server has no list operation and never enumerates the
bucket.

Minimal AWS-style policy shape (replace the bucket ARN):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::someday-private-media",
      "Condition": {
        "StringLike": { "s3:prefix": "media/v1/*" }
      }
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": "arn:aws:s3:::someday-private-media/media/v1/*"
    }
  ]
}
```

The standalone topology changes only the media settings:

```bash
export SOMEDAY_MEDIA_BACKEND=filesystem
export SOMEDAY_MEDIA_BLOB_DIR=/var/lib/someday/media
```

The directory must be an app-owned durable mount, not the container's writable
layer.

Keep these values in an operator-owned secret store or a permission-restricted
service environment file. Do not put them in Compose files, shell history, or
the repository.

The application database role must own (or be allowed to migrate) its Someday
schema, but it should not be a PostgreSQL superuser and must not have
`BYPASSRLS`. Entity and media tables use forced account/workspace row-level
security as defense in depth; a privileged role would bypass that layer.

Runtime and storage settings:

| Variable | Production default | Meaning |
| --- | --- | --- |
| `SOMEDAY_HOST` | `127.0.0.1` | Ktor bind address. Keep loopback when the reverse proxy is on the same host. |
| `SOMEDAY_PORT` | `3180` | Ktor listen port. |
| `SOMEDAY_REGISTRATION_ENABLED` | `false` | Whether anyone reaching `/auth/register` may create an account. |
| `SOMEDAY_TRUST_PROXY_HEADERS` | `false` | Trust the first `X-Forwarded-For` value for rate limiting. Enable only behind a proxy that strips client-supplied forwarding headers. |
| `SOMEDAY_ARGON2_MAX_CONCURRENT` | `2` | Maximum concurrent Argon2 password operations. |
| `SOMEDAY_RATE_LIMIT_MAX_ATTEMPTS` | `5` | Authentication attempts per account and client window. |
| `SOMEDAY_RATE_LIMIT_WINDOW_SECONDS` | `60` | Authentication rate-limit window. |
| `SOMEDAY_RATE_LIMIT_MAX_BUCKETS` | `10000` | Hard in-memory ceiling for rate-limit keys. |
| `SOMEDAY_SYSTEM_V3_RATE_LIMIT_MAX_ATTEMPTS` | `256` | Per-device System V3 entity/media requests in the shared rate-limit window. |
| `SOMEDAY_DB_MAX_POOL_SIZE` | `10` | Maximum shared PostgreSQL connections. Accepted range: 1–32. |
| `SOMEDAY_MEDIA_BACKEND` | none | `filesystem` or `s3`; required in production. |
| `SOMEDAY_MEDIA_BLOB_DIR` | none | Absolute app-owned ciphertext directory; required only for `filesystem`. |
| `SOMEDAY_MEDIA_S3_BUCKET` | none | Private dedicated bucket; required only for `s3`. |
| `SOMEDAY_MEDIA_S3_REGION` | none | S3 signing region; required only for `s3`. |
| `SOMEDAY_MEDIA_S3_ENDPOINT` | provider default | Optional HTTP(S) origin for a compatible service. Use TLS outside isolated networks. |
| `SOMEDAY_MEDIA_S3_PATH_STYLE` | `false` | Enable path-style addressing only when the compatible service requires it. |
| `SOMEDAY_MEDIA_QUOTA_BYTES` | `5368709120` | Maximum PostgreSQL-indexed published media ciphertext bytes per account, summed across all workspaces. |

All Boolean settings accept only `true` or `false`. Malformed values fail
startup rather than silently changing policy.

## TLS and reverse proxy

Expose the service only through an HTTPS reverse proxy. The proxy must preserve
the original `Host` and should set `X-Forwarded-For` after discarding any value
supplied by the client. Keep PostgreSQL private; never publish its port to the
internet.

Example Caddy configuration:

```caddyfile
notes.example.com {
    reverse_proxy 127.0.0.1:3180
}
```

If the reverse proxy runs on the same host, leave `SOMEDAY_HOST=127.0.0.1`.
Set `SOMEDAY_TRUST_PROXY_HEADERS=true` only after the proxy boundary is
enforced. `SOMEDAY_PUBLIC_BASE_URL` must exactly match the browser-visible
origin because admin POST requests use strict Origin validation. Clients also
configure a bare origin only: credentials, path prefixes, queries, and
fragments are rejected rather than becoming part of the sync authority.

For an initialized workspace the client persists a fail-closed publication
binding containing the canonical endpoint, authenticated `userId`, canonical
`workspaceId`, and local writer device id. It is established before the first
remote write.
If its refresh session expires or is missing, setup decodes that persisted
authority, rejects another endpoint before authentication, verifies the
authenticated immutable `userId` before device registration, and requests new
tokens for the exact stable non-revoked device id. It cannot silently select
another account, workspace, or replacement writer.

## Build and start

### JVM distribution

Use a JDK 21 environment to create the distribution. Copy it to any server with
a Java 21 runtime and run it under a dedicated unprivileged service account:

```bash
./gradlew :server:installDist
server/build/install/server/bin/server
```

Apply operating-system service supervision, restart policy, an appropriate log
lifecycle, database and media backups, and security updates appropriate to the
host.

### Docker

The root `Dockerfile` builds one non-root Java 21 image for both storage
topologies. Its root filesystem can remain read-only. The top-level
`compose.yaml` remains a development-only PostgreSQL dependency; production
examples live under `deploy/`.

For the recommended external topology, provision PostgreSQL and a private
S3-compatible bucket first, then:

```bash
cd deploy/external
cp .env.example .env
chmod 600 .env
# Fill every required value in .env.
docker compose up -d --build
```

For an all-local installation with durable PostgreSQL and media volumes:

```bash
cd deploy/standalone
cp .env.example .env
chmod 600 .env
# Fill every required value in .env.
docker compose up -d --build
```

Both examples publish Ktor on loopback only. Put an HTTPS reverse proxy in
front of that port. `docker compose down` preserves named volumes;
`docker compose down --volumes` destroys the standalone database and media and
must not be used as a routine stop command.

The unauthenticated health endpoint is:

```text
GET /health
```

It reports only service availability. Do not use the admin dashboard as an
internet-facing health probe.

## Backup and recovery

PostgreSQL metadata and the configured media store form one logical recovery
unit. A database-only backup can recover note DAG metadata but leaves published
image objects unavailable; a media-only backup has no authenticated
account/workspace/object index. Retain the configured JWT secret separately and
test complete restoration into an isolated instance.

For the standalone filesystem backend, coordinate PostgreSQL snapshots with
off-host copies of `SOMEDAY_MEDIA_BLOB_DIR`. A Docker volume protects data from
container replacement but not host, disk, or operator loss.

For the recommended external topology, PostgreSQL point-in-time recovery and
bucket versioning/retention replace the local-directory copy. Keep portable
database dumps and, where required, an off-provider object copy so a provider
account is not the only recovery path. The recovery set is complete only when
every PostgreSQL media record has an object with the exact expected key,
length, and actual-byte SHA-256; extra unreferenced objects are harmless.
Quiesce writes while capturing both sides or run the operator integrity
validator after an online capture. Matching timestamps alone are not
sufficient.
The complete ordering and restore invariants are defined in
`server-storage-architecture.md`.

Validate a restored recovery set before making it live:

```bash
./gradlew :server:verifyMediaIntegrity
```

With either production Compose example, the equivalent command is:

```bash
docker compose run --rm server verify-media-integrity
```

The command enumerates PostgreSQL media rows, reads each referenced object
within the protocol bound, hashes its bytes, and does not list or mutate the
media store. Exit status `0` means all referenced objects matched, `2` means
the recovery set is incomplete or divergent, and `1` means verification itself
could not complete. Extra unreferenced immutable objects are allowed.

The app's portable export/restore format contains structured notebook/note
records and may retain unresolved media references. It contains no workspace
keys, recovery material, sessions, or app-private image bytes. It is not an
operator backup substitute and cannot by itself restore images on a new
installation.

## Bootstrap the administrator

Public registration is not needed to create the first account. The bootstrap
command inserts one new administrator atomically and refuses to promote or
replace an existing account.

Prefer a password file readable only by the operator:

```bash
export SOMEDAY_ADMIN_EMAIL=owner@example.com
export SOMEDAY_ADMIN_PASSWORD_FILE=/run/secrets/someday-admin-password
./gradlew :server:bootstrapAdmin
```

The production image exposes the same operation as `bootstrap-admin`. Compose
allocates an interactive terminal by default, so the simplest safe invocation
prompts without placing the password in the environment or shell history:

```bash
docker compose run --rm \
  -e SOMEDAY_ADMIN_EMAIL=owner@example.com \
  server bootstrap-admin
```

For non-interactive automation, mount a password file and set
`SOMEDAY_ADMIN_PASSWORD_FILE`. The file must be readable by the fixed container
identity `10001:10001`; do not make it world-readable.

`SOMEDAY_ADMIN_PASSWORD` is supported for constrained environments, but a
secret file or interactive console is safer. The administrator account can
also be used by a Someday client. To permit additional users, deliberately set
`SOMEDAY_REGISTRATION_ENABLED=true` and restart; close registration again when
the intended accounts exist.

The dashboard lives at `/admin`. Browser sessions use `HttpOnly`,
`SameSite=Strict`, `Secure` cookies in production; state-changing requests also
require the configured same-origin `Origin` header. Admin pages send
`Cache-Control: no-store`, CSP, frame denial, and content-type hardening headers.

## Security boundaries

- Authentication is rate-limited independently by client address and account.
  The limiter has a strict bucket ceiling and expired-bucket eviction.
- Passwords are limited to 128 characters before Argon2 work. Concurrent Argon2
  work is bounded, and unknown-account logins perform a dummy Argon2
  verification to reduce account-enumeration timing differences.
- A single process has an in-memory limiter. Multi-instance deployments need a
  shared edge or distributed limiter before they can claim a cluster-wide
  attempt budget.
- Database administrators and host operators can delete or deny access to
  ciphertext. End-to-end encryption does not provide availability against the
  operator.
