# Self-hosting Someday

Someday Server is the remote synchronization authority. It stores account and
device metadata together with client-encrypted notes and images. Plaintext
workspace keys remain on clients; the server may also store one opaque,
recovery-code-wrapped key envelope for each account.

## What you need

The recommended external deployment has four operator-visible resources:

| Resource | Count | Purpose |
| --- | ---: | --- |
| Someday Server container | 1 | Disposable application process with no durable user data |
| PostgreSQL 17 database | 1 | Accounts, recovery envelopes, sync state, encrypted notes, and image metadata |
| Private S3-compatible bucket | 1 | Encrypted image objects |
| HTTPS origin | 1 | Stable address configured in every client |

One database and one bucket are enough for normal operation. A second isolated
database or bucket is only needed while rehearsing recovery; it is not part of
the running service.

The standalone deployment instead needs one Docker host. Its Compose project
runs Someday Server and PostgreSQL, then keeps database and image data in two
named volumes on that host.

## Choose a topology

| Topology | Durable storage | Use when |
| --- | --- | --- |
| [External](self-hosting-external.md) (recommended) | PostgreSQL 17 and a private S3-compatible bucket | You want replaceable app containers and managed storage |
| [Standalone](self-hosting-standalone.md) | PostgreSQL and a media volume on one Docker host | You operate and back up one server |

Both use the same image and System V3 protocol. The client configuration is
identical; only the server's storage settings differ.

## Deployment path

1. Choose a `server-vX.Y.Z` release and download its verified deployment bundle.
2. Follow the [external](self-hosting-external.md) or
   [standalone](self-hosting-standalone.md) Docker guide.
3. Point a stable HTTPS origin at the server and confirm `/health` succeeds.
4. Run `bootstrap-admin`, then sign in to `/admin`.
5. Configure the first client with the HTTPS origin, email, and password.
6. Pair additional clients so they receive the workspace key.
7. Complete the [backup and recovery checklist](server-backup-and-recovery.md)
   before storing real data.

For later releases, follow the [upgrade runbook](server-upgrades.md).

## Supported runtime

- Docker Engine 24 or newer and Docker Compose 2.20 or newer for the published
  image.
- Java 21 for a source-built JVM distribution.
- PostgreSQL 17. A dedicated database and its `public` schema must not be
  shared with another application.
- Run one server process. The current server does not support multiple replicas
  or transaction-mode database poolers.

## Published image

Server releases use `server-vX.Y.Z`; clients use `vX.Y.Z`. Copy the image
reference from the matching GitHub Release:

```text
ghcr.io/sensefy/someday-server:X.Y.Z@sha256:<release-digest>
```

The tag identifies the version. The digest locks the deployed bytes. The
release bundle already places this exact reference in each `.env.example`.

## Production configuration

Production mode requires an HTTPS public origin, a stable JWT secret, a
database TLS mode, and one media backend:

```text
SOMEDAY_DEPLOYMENT_MODE=production
SOMEDAY_PUBLIC_BASE_URL=https://notes.example.com
SOMEDAY_DB_TLS_MODE=private|verify-full
SOMEDAY_MEDIA_BACKEND=filesystem|s3
```

Use `private` only for loopback or a private Docker network. External
PostgreSQL uses `verify-full`, which verifies its certificate and hostname.
Public CA certificates use Java's standard trust store; a private CA is
supplied through the JDBC `sslrootcert` path.

The application database role must own and migrate Someday's schema. It must
not be a superuser or have `BYPASSRLS`.

## HTTPS

The Docker guides publish port `3180` on host loopback. Terminate public HTTPS
with a reverse proxy. A minimal Caddy configuration is:

```caddyfile
notes.example.com {
    reverse_proxy 127.0.0.1:3180
}
```

Set `SOMEDAY_TRUST_PROXY_HEADERS=true` only when the proxy removes incoming
forwarding headers before setting its own.

## First administrator and clients

Create the first account without opening registration:

```bash
docker compose run --rm \
  -e SOMEDAY_ADMIN_EMAIL=owner@example.com \
  server bootstrap-admin
```

The command prompts for the password. For automation, mount a file readable by
container user `10001:10001` and set `SOMEDAY_ADMIN_PASSWORD_FILE`.

The dashboard is at `/admin`. Keep `SOMEDAY_REGISTRATION_ENABLED=false` unless
you want open account creation.

Configure a client with the public HTTPS origin and account credentials. Do
not append an API path, query, or fragment. Signing in identifies the server
account; pairing transfers the end-to-end encrypted workspace key to another
device.

## Run without Docker

On any Java 21 host, build the source distribution from an exact server tag:

```bash
./gradlew :server:installDist
```

Export the same `SOMEDAY_*` settings required by the selected topology. The
distribution provides:

```text
server/build/install/server/bin/server
server/build/install/server/bin/bootstrap-admin
server/build/install/server/bin/verify-media-integrity
```

The operator supplies PostgreSQL 17, durable filesystem or S3 storage, a
process supervisor, and an HTTPS reverse proxy. Docker and source builds use
the same configuration requirements.

## Storage and recovery boundary

PostgreSQL contains accounts, devices, pairing state, account-current recovery
envelopes, encrypted entity DAGs, cursors, and media metadata. The media backend
contains immutable encrypted image objects. Back up and restore them as one
recovery unit.

Portable client export excludes image bytes and workspace keys, so it does not
replace a server backup. Reading a restored workspace requires either an intact
device or the recovery code matching the restored envelope revision. Operators
must not collect users' codes. The persistence and object-storage invariants are
documented in [Server Storage Architecture](server-storage-architecture.md),
and the user-key flow in
[Workspace Recovery Protocol](workspace-recovery-protocol.md).
