# Standalone Docker quickstart

This topology runs Someday Server and PostgreSQL 17 on one Docker host. Note
metadata and encrypted entity objects live in the PostgreSQL volume; encrypted
images live in a separate media volume.

## 1. Get the exact release

Replace `X.Y.Z` and `<release-digest>` with values from the matching GitHub
Release. Its deployment bundle already fills the image reference.

```bash
git clone --depth 1 --branch server-vX.Y.Z \
  https://github.com/sensefy/someday.git
cd someday/deploy/standalone
cp .env.example .env
chmod 600 .env
```

Set these values in `.env`:

```dotenv
SOMEDAY_IMAGE=ghcr.io/sensefy/someday-server:X.Y.Z@sha256:<release-digest>
SOMEDAY_PUBLIC_BASE_URL=https://notes.example.com
SOMEDAY_DB_PASSWORD=<random application password>
SOMEDAY_POSTGRES_ADMIN_PASSWORD=<different random admin password>
SOMEDAY_JWT_SECRET=<at least 32 random bytes>
```

Generate each secret independently. For example:

```bash
openssl rand -base64 48
```

## 2. Start

```bash
docker compose pull
docker compose up -d
docker compose ps
curl --fail http://127.0.0.1:3180/health
```

The app port is reachable only on host loopback. Add the HTTPS reverse proxy
from [Self-hosting Someday](self-hosting.md), then verify:

```bash
curl --fail https://notes.example.com/health
```

## 3. Create the administrator and connect

```bash
docker compose run --rm \
  -e SOMEDAY_ADMIN_EMAIL=owner@example.com \
  server bootstrap-admin
```

Open `https://notes.example.com/admin`, then connect a Someday client with the
same origin and account.

Before adding real notes, complete
[Backup and Recovery](server-backup-and-recovery.md).

## Operations

```bash
docker compose logs -f server
docker compose stop
docker compose start
```

`docker compose down` preserves named volumes. Do not use
`docker compose down --volumes` unless you intend to destroy both database and
media storage.

To build the exact source tag instead of pulling the release image:

```bash
docker compose -f compose.yaml -f compose.source.yaml build server
docker compose -f compose.yaml -f compose.source.yaml up -d
```

The source override uses the local image `someday-server:source`; it does not
replace the digest-pinned release reference in `.env`.
