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

`compose.yaml` is a **local development dependency stack**, not a production
deployment. Its PostgreSQL port binds to loopback and its credentials are
intentionally well-known.

## Deployment modes

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

## Required production configuration

Set all of the following before starting the service:

```bash
export SOMEDAY_PUBLIC_BASE_URL=https://notes.example.com
export SOMEDAY_DB_URL=jdbc:postgresql://database.internal:5432/someday
export SOMEDAY_DB_USER=someday_app
export SOMEDAY_DB_PASSWORD='<random database password>'
export SOMEDAY_JWT_SECRET="$(openssl rand -base64 48)"
export SOMEDAY_MEDIA_BLOB_DIR=/var/lib/someday/media
```

Keep these values in an operator-owned secret store or a permission-restricted
service environment file. Do not put them in Compose files, shell history, or
the repository.

The application database role must own (or be allowed to migrate) its Someday
schema, but it should not be a PostgreSQL superuser and must not have
`BYPASSRLS`. Entity and media tables use forced account/workspace row-level
security as defense in depth; a privileged role would bypass that layer.

Useful optional settings:

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
| `SOMEDAY_MEDIA_BLOB_DIR` | none | Absolute app-owned directory for encrypted media ciphertext. Required in production. |
| `SOMEDAY_MEDIA_QUOTA_BYTES` | `5368709120` | Maximum stored media ciphertext bytes per account, summed across all of that account's workspaces. |

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

Create the server distribution:

```bash
./gradlew :server:installDist
```

Run `server/build/install/server/bin/server` under a dedicated unprivileged
service account with the production environment above. Apply operating-system
service supervision, restart policy, an appropriate log lifecycle, database
backups, and security updates appropriate to the host.

The unauthenticated health endpoint is:

```text
GET /health
```

It reports only service availability. Do not use the admin dashboard as an
internet-facing health probe.

## Backup and recovery

PostgreSQL metadata and `SOMEDAY_MEDIA_BLOB_DIR` form one logical storage unit.
Back up both from a consistent point in time, retain the configured JWT secret
separately, and test restoring into an isolated instance. A database-only
backup can recover note DAG metadata but leaves published image objects
unavailable; a blob-only backup has no authenticated account/workspace/object
index. Record the database snapshot boundary before copying incremental blob
data so the two parts can be restored coherently.

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
