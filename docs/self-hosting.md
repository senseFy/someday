# Self-hosting Someday

Someday Server is the only remote synchronization authority. It stores
client-encrypted note objects and images, plus account and device metadata.
Workspace keys remain on paired clients.

## Choose a topology

| Topology | Durable storage | Use when |
| --- | --- | --- |
| [Standalone](self-hosting-standalone.md) | PostgreSQL and a media volume on one Docker host | You operate and back up one server |
| [External](self-hosting-external.md) | PostgreSQL 17 and a private S3-compatible bucket | You want replaceable app containers and managed storage |

Both use the same server image and System V3 protocol. External storage is the
recommended production topology; standalone remains fully supported.

Before storing real data, complete the
[backup and recovery checklist](server-backup-and-recovery.md). For releases
after the first one, follow the [upgrade runbook](server-upgrades.md).

## Supported runtime

- Docker Engine 24 or newer and Docker Compose 2.20 or newer for the published
  image.
- Java 21 for a source-built JVM distribution.
- PostgreSQL 17. A dedicated database and its `public` schema must not be
  shared with another application.
- One server process. Multiple replicas and transaction-mode database poolers
  are not supported in the first release.

Server releases use `server-vX.Y.Z`; clients keep `vX.Y.Z`. Deploy the image
reference recorded in the matching GitHub Release:

```text
ghcr.io/sensefy/someday-server:X.Y.Z@sha256:<release-digest>
```

The tag shows the version; the digest locks the image bytes.

## Run without Docker

On any Java 21 host, build the source distribution from an exact server tag:

```bash
./gradlew :server:installDist
```

Export the same `SOMEDAY_*` settings required by the chosen topology. The
distribution provides these entry points:

```text
server/build/install/server/bin/server
server/build/install/server/bin/bootstrap-admin
server/build/install/server/bin/verify-media-integrity
```

The operator supplies PostgreSQL 17, durable filesystem or S3 storage, a
process supervisor, and an HTTPS reverse proxy. The Docker and source
distributions use the same runtime contract.

## Common production contract

Production mode requires an HTTPS public origin, a stable JWT secret, an
explicit database TLS policy, and one media backend:

```text
SOMEDAY_DEPLOYMENT_MODE=production
SOMEDAY_PUBLIC_BASE_URL=https://notes.example.com
SOMEDAY_DB_TLS_MODE=private|verify-full
SOMEDAY_MEDIA_BACKEND=filesystem|s3
```

Use `private` only for loopback or a private Docker network. External
PostgreSQL uses `verify-full`, which verifies both the certificate and
hostname. Public CA certificates use Java's standard trust store; a private CA
is supplied through the JDBC `sslrootcert` path. The application role must own
and migrate Someday's schema, and must be neither superuser nor `BYPASSRLS`.

Docker publishes the server port on host loopback in the quickstarts. Terminate
public HTTPS with a host-native reverse proxy. A minimal Caddy configuration is:

```caddyfile
notes.example.com {
    reverse_proxy 127.0.0.1:3180
}
```

Set `SOMEDAY_TRUST_PROXY_HEADERS=true` only when that proxy removes incoming
forwarding headers before setting its own.

## First administrator

Create the first account without opening registration:

```bash
docker compose run --rm \
  -e SOMEDAY_ADMIN_EMAIL=owner@example.com \
  server bootstrap-admin
```

The command prompts for the password. For automation, mount a file readable by
container user `10001:10001` and set `SOMEDAY_ADMIN_PASSWORD_FILE`.

The dashboard is at `/admin`. Keep `SOMEDAY_REGISTRATION_ENABLED=false` unless
you deliberately want new account creation.

## Health and client connection

The unauthenticated probe is:

```text
GET /health
```

After HTTPS and the administrator account work, configure a client with only
the public origin and account credentials. Do not append an API path, query, or
fragment.

## Storage boundary

PostgreSQL contains accounts, devices, pairing state, encrypted entity DAGs,
cursors, and media metadata. The media backend contains immutable encrypted
image objects. These two stores are one recovery unit.

Portable client export still excludes image bytes and workspace keys. It does
not replace a server backup. Readable server recovery requires at least one
intact paired device.

The full persistence and S3 invariants are in
[Server Storage Architecture](server-storage-architecture.md).
