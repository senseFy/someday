# External storage Docker quickstart

This topology keeps the Someday Server container disposable. Durable state
lives in one PostgreSQL 17 database and one private S3-compatible bucket.

Before starting, prepare a Docker host, a stable HTTPS hostname, the database,
and the bucket. A second database or bucket is not required for normal
operation.

## 1. Provision PostgreSQL

Create a dedicated database whose `public` schema is used only by Someday. The
application role must:

- own and migrate the schema;
- be a login role;
- have neither superuser nor `BYPASSRLS`; and
- connect through a direct, session-affine endpoint on port `5432`.

Transaction-mode poolers are unsupported because row-level security uses
session settings. The runtime checks Someday's required RLS policies, and the
schema cannot be shared with another application.

Record the database host, database name, application username, and application
password. Keep privileged provisioning credentials out of the server `.env`.

## 2. Provision object storage

Create one private, dedicated bucket. It must provide:

- single-object HEAD, GET, and conditional PUT with `If-None-Match: *`;
- strong read-after-write behavior;
- stable user metadata for the ciphertext SHA-256;
- a distinguishable missing-object response for HEAD and GET; and
- no expiry rule that can remove `media/v1/*`.

The runtime uses only `media/v1/*`, including the bounded startup probe at
`media/v1/.someday-system/startup-probe-v1.bin`. It does not list or delete
objects. Provider-specific permissions may still be needed to make a missing
object return `404` instead of an authorization error.

Create bucket-scoped read/write credentials and record the bucket name,
endpoint, signing region, access key, and secret key.

## 3. Download an exact release

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
cd "$ASSET/deploy/external"
cp .env.example .env
chmod 600 .env
```

The release-generated `.env.example` already contains the digest-pinned image
reference. Keep that value unchanged.

## 4. Configure the server

Set the required values in the `.env` file in the current
`deploy/external` directory:

```dotenv
SOMEDAY_PUBLIC_BASE_URL=https://notes.example.com
SOMEDAY_DB_URL=jdbc:postgresql://db.example.com:5432/someday
SOMEDAY_DB_USER=someday_app
SOMEDAY_DB_PASSWORD=<database password>
SOMEDAY_JWT_SECRET=<at least 32 random bytes>

SOMEDAY_MEDIA_S3_BUCKET=someday-private-media
SOMEDAY_MEDIA_S3_REGION=<signing region>
```

Generate the JWT secret independently, for example:

```bash
openssl rand -base64 48
```

For a non-AWS S3-compatible provider, set its HTTPS endpoint. Enable path-style
addressing only when the provider requires it:

```dotenv
SOMEDAY_MEDIA_S3_ENDPOINT=https://<provider endpoint>
SOMEDAY_MEDIA_S3_PATH_STYLE=true
```

Credentials use the AWS SDK default provider chain. If the host does not
supply an instance or container role, add bucket-scoped static credentials:

```dotenv
AWS_ACCESS_KEY_ID=<bucket-scoped key>
AWS_SECRET_ACCESS_KEY=<bucket-scoped secret>
```

External Compose fixes `SOMEDAY_DB_TLS_MODE=verify-full`. PostgreSQL endpoints
signed by a CA in Java's standard trust store need no extra setting. For a
private CA, add `sslrootcert` to the JDBC URL and create
`compose.override.yaml` in the current directory to mount the PEM file at the
same container path:

```dotenv
SOMEDAY_DB_URL=jdbc:postgresql://db.example.com:5432/someday?sslrootcert=/run/secrets/postgres-root.crt
```

```yaml
services:
  server:
    volumes:
      - ./postgres-root.crt:/run/secrets/postgres-root.crt:ro
```

Use `&` instead of `?` when the JDBC URL already has a query.

## 5. Start the container

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

## 6. Add HTTPS

Point the hostname from `SOMEDAY_PUBLIC_BASE_URL` at the Docker host and place
an HTTPS reverse proxy in front of `127.0.0.1:3180`. The shared
[self-hosting guide](self-hosting.md#https) includes a minimal Caddy example.

Then verify the public endpoint:

```bash
curl --fail https://notes.example.com/health
```

The public health URL must use the same origin stored in `.env`.

## 7. Create the administrator and connect clients

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
email, and the same password. Enter the origin without an API path. Pair each
additional device from an existing workspace so it receives the workspace key.

## 8. Protect the data

Check that every PostgreSQL media record has a matching object:

```bash
docker compose run --rm --no-deps server verify-media-integrity
```

Before storing real notes, complete
[Backup and Recovery](server-backup-and-recovery.md). PostgreSQL and the bucket
are one recovery unit; provider snapshots alone do not prove a complete
restore.

## PlanetScale PostgreSQL and Cloudflare R2

Someday's first named managed profile combines PlanetScale PostgreSQL with
Cloudflare R2.

PlanetScale settings:

- PostgreSQL 17;
- a direct endpoint on port `5432`;
- `verify-full` TLS; and
- a custom schema-owning role without superuser or `BYPASSRLS`.

PlanetScale's default `postgres` role has `BYPASSRLS` and is unsuitable as the
application role. Keep it only for provisioning and recovery.

R2 settings:

```dotenv
SOMEDAY_MEDIA_S3_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
SOMEDAY_MEDIA_S3_REGION=auto
SOMEDAY_MEDIA_S3_PATH_STYLE=true
```

- Keep the bucket private and disable public development/custom URLs.
- Create a non-admin `Object Read & Write` token scoped to this bucket.
- Set an indefinite Bucket Lock rule on `media/v1/`.
- Exclude `media/v1/` from expiry lifecycle rules.
- Keep a recovery copy outside Cloudflare when provider/account loss is in
  scope.

Release maintainers validate this profile with the repository's destructive
[managed storage gates](managed-storage-profile-gates.md), using separate
disposable resources rather than a live deployment.
