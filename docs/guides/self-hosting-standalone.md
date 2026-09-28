# Standalone Docker quickstart

This topology runs Someday Server and PostgreSQL 17 on one Docker host. Note
metadata, opaque recovery envelopes, and encrypted entity objects live in the
PostgreSQL volume; encrypted images live in a separate media volume.

Prepare a host with Docker Engine 24 or newer, Docker Compose 2.20 or newer,
and a stable HTTPS hostname.

## 1. Download an exact release

Replace `X.Y.Z` with the server version shown in
[GitHub Releases](https://github.com/senseFy/someday/releases):

```bash
VERSION=X.Y.Z
ASSET="someday-server-$VERSION"

curl --fail --location --remote-name \
  "https://github.com/senseFy/someday/releases/download/server-v$VERSION/$ASSET.tar.gz"
curl --fail --location --remote-name \
  "https://github.com/senseFy/someday/releases/download/server-v$VERSION/$ASSET.tar.gz.sha256"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum --check "$ASSET.tar.gz.sha256"
else
  shasum -a 256 --check "$ASSET.tar.gz.sha256"
fi

tar -xzf "$ASSET.tar.gz"
cd "$ASSET/deploy/standalone"
cp .env.example .env
chmod 600 .env
```

The release-generated `.env.example` already contains the digest-pinned image
reference. Keep that value unchanged.

## 2. Configure the server

Set these values in the `.env` file in the current `deploy/standalone`
directory:

```dotenv
SOMEDAY_PUBLIC_BASE_URL=https://notes.example.com
SOMEDAY_DB_PASSWORD=<random application password>
SOMEDAY_POSTGRES_ADMIN_PASSWORD=<different random admin password>
SOMEDAY_JWT_SECRET=<at least 32 random bytes>
```

Generate all three secrets independently, for example:

```bash
openssl rand -base64 48
```

Keep `.env` with the protected deployment backup and outside version control.

## 3. Start the services

Validate the resolved configuration before starting:

```bash
docker compose config
docker compose pull
docker compose up -d
docker compose ps
curl --fail http://127.0.0.1:3180/health
```

If the health check fails, inspect the server before continuing:

```bash
docker compose logs --tail=200 server
```

The published port is bound to host loopback, not directly to the internet.

## 4. Add HTTPS

Point the hostname from `SOMEDAY_PUBLIC_BASE_URL` at the Docker host and place
an HTTPS reverse proxy in front of `127.0.0.1:3180`. The shared
[self-hosting guide](self-hosting.md#https) includes a minimal Caddy example.

Then verify the public endpoint:

```bash
curl --fail https://notes.example.com/health
```

The public health URL must use the same origin stored in `.env`.

## 5. Create the administrator and connect clients

Create the first account without enabling public registration:

```bash
docker compose run --rm \
  -e SOMEDAY_ADMIN_EMAIL=owner@example.com \
  server bootstrap-admin
```

Enter the password at the prompt, then sign in at:

```text
https://notes.example.com/admin
```

Configure the first Someday client with `https://notes.example.com`, the same
email, and the same password. Enter the origin without an API path. The password
authenticates the account but does not decrypt its workspace. After the first
successful sync, set up a recovery code, save it outside Someday, and enter it
again to publish the opaque recovery envelope. Pair another device from the
existing workspace when possible, or recover a fresh client with the saved
code.

## 6. Protect the data

Check that every PostgreSQL media record has a matching image object:

```bash
docker compose run --rm --no-deps server verify-media-integrity
```

Before storing real notes, complete
[Backup and Recovery](server-backup-and-recovery.md). The PostgreSQL and media
volumes are one recovery unit, and PostgreSQL contains the account recovery
envelopes. Docker volumes provide persistence, not backup. Never collect users'
recovery codes as part of an operator backup.

## Operations

```bash
docker compose logs -f server
docker compose stop
docker compose start
```

`docker compose down` preserves named volumes. Do not use
`docker compose down --volumes` unless you intend to destroy both database and
media storage.

## Build from the exact source tag

The release bundle deploys the published image. To build the same version from
source instead, clone its exact server tag:

```bash
VERSION=X.Y.Z
git clone --depth 1 --branch "server-v$VERSION" \
  https://github.com/senseFy/someday.git "someday-source-$VERSION"
cd "someday-source-$VERSION/deploy/standalone"
cp .env.example .env
# Edit .env before continuing.
docker compose -f compose.yaml -f compose.source.yaml build server
docker compose -f compose.yaml -f compose.source.yaml up -d
```

The source override uses the local image `someday-server:source`; it does not
replace the digest-pinned release reference in `.env`.
