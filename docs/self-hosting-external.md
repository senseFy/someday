# External storage Docker quickstart

This topology keeps the application container disposable. Durable state lives
in PostgreSQL 17 and a private S3-compatible bucket.

## 1. Provision PostgreSQL

Create a dedicated database whose `public` schema is used only by Someday. The
application role must own and migrate that schema. It must be a login role with
`NOSUPERUSER` and `NOBYPASSRLS`.

Use a direct endpoint on port `5432`. Transaction-mode poolers are unsupported
because row-level security uses session settings. The runtime checks the exact
Someday RLS catalog, so sharing the schema with another application is not
supported.

## 2. Provision object storage

Create a private dedicated bucket. It must provide:

- single-object HEAD, GET, and conditional PUT with `If-None-Match: *`;
- strong read-after-write behavior;
- stable user metadata for the ciphertext SHA-256;
- a distinguishable missing-object response for HEAD and GET; and
- no expiry rule that can remove `media/v1/*`.

The runtime uses only `media/v1/*`, including its bounded startup probe at
`media/v1/.someday-system/startup-probe-v1.bin`. It does not list or delete
objects. Provider-specific permissions may still be needed to make a missing
object return `404` instead of an authorization error.

## 3. Configure and start

Replace `X.Y.Z` in these commands with the server version. The release bundle
contains the digest-pinned image reference.

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

Keep the generated `SOMEDAY_IMAGE` value and set the remaining required values
in `.env`:

```dotenv
SOMEDAY_PUBLIC_BASE_URL=https://notes.example.com
SOMEDAY_DB_URL=jdbc:postgresql://db.example.com:5432/someday
SOMEDAY_DB_USER=someday_app
SOMEDAY_DB_PASSWORD=<database password>
SOMEDAY_JWT_SECRET=<at least 32 random bytes>
SOMEDAY_MEDIA_S3_BUCKET=someday-private-media
SOMEDAY_MEDIA_S3_REGION=<signing region>
```

For a non-AWS S3-compatible provider, set its HTTPS endpoint. Enable path-style
addressing only when the provider requires it:

```dotenv
SOMEDAY_MEDIA_S3_ENDPOINT=https://<provider endpoint>
SOMEDAY_MEDIA_S3_PATH_STYLE=true
```

Credentials use the AWS SDK default provider chain. If the host does not supply
an instance or container role, set bucket-scoped static credentials:

```dotenv
AWS_ACCESS_KEY_ID=<bucket-scoped key>
AWS_SECRET_ACCESS_KEY=<bucket-scoped secret>
```

External Compose fixes `SOMEDAY_DB_TLS_MODE=verify-full`. The S3 endpoint must
also use HTTPS. PostgreSQL endpoints signed by a CA in Java's standard trust
store need no extra setting. For a private CA, add
`?sslrootcert=/run/secrets/postgres-root.crt` to `SOMEDAY_DB_URL` (use `&`
instead of `?` when the URL already has a query), then bind-mount that PEM file
read-only at the same container path with `compose.override.yaml`:

```dotenv
SOMEDAY_DB_URL=jdbc:postgresql://db.example.com:5432/someday?sslrootcert=/run/secrets/postgres-root.crt
```

```yaml
services:
  server:
    volumes:
      - ./postgres-root.crt:/run/secrets/postgres-root.crt:ro
```

Start and check the service:

```bash
docker compose pull
docker compose up -d
docker compose ps
curl --fail http://127.0.0.1:3180/health
```

Add the HTTPS reverse proxy from [Self-hosting Someday](self-hosting.md), create
the first administrator, and connect a client. Complete the
[recovery checklist](server-backup-and-recovery.md) before adding real data.

## PlanetScale PostgreSQL and Cloudflare R2 profile

This is the first named managed profile. It remains a candidate until its live
gate has passed for a release.

PlanetScale settings:

- PostgreSQL 17;
- direct endpoint on port `5432`;
- `verify-full` TLS; and
- a custom schema-owning role without superuser or `BYPASSRLS`.

Do not use PlanetScale's default `postgres` role as the application role; it
has `BYPASSRLS`. Keep any privileged role only for provisioning and recovery.

R2 settings:

```dotenv
SOMEDAY_MEDIA_S3_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
SOMEDAY_MEDIA_S3_REGION=auto
SOMEDAY_MEDIA_S3_PATH_STYLE=true
```

- Keep the bucket private and disable public development/custom URLs.
- Create a non-admin `Object Read & Write` token scoped to this bucket.
- Set an indefinite Bucket Lock rule on `media/v1/`.
- Do not apply an expiry lifecycle rule to `media/v1/`.
- Keep a recovery copy outside Cloudflare when provider/account loss is in
  scope.

The repository's [managed storage gates](managed-storage-profile-gates.md)
check this profile against dedicated PlanetScale and R2 test resources.
