# Self-hosting Someday

The Ktor service provides authenticated device management, opaque Sync V2
storage, pairing transport, and an operator dashboard. Note content and
workspace keys remain client-encrypted, but account metadata, device metadata,
availability, and deletion controls are still security-sensitive.

`compose.yaml` is a **local development dependency stack**, not a production
deployment. Its PostgreSQL and WebDAV ports bind to loopback and its credentials
are intentionally well-known.

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
```

Keep these values in an operator-owned secret store or a permission-restricted
service environment file. Do not put them in Compose files, shell history, or
the repository.

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
origin because admin POST requests use strict Origin validation.

## Build and start

Create the server distribution:

```bash
./gradlew :server:installDist
```

Run `server/build/install/server/bin/server` under a dedicated unprivileged
service account with the production environment above. Apply operating-system
service supervision, restart policy, log retention, database backups, and
security updates appropriate to the host.

The unauthenticated health endpoint is:

```text
GET /health
```

It reports only service availability. Do not use the admin dashboard as an
internet-facing health probe.

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
