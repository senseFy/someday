# Managed storage profile gates

These maintainer gates validate named services against Someday's PostgreSQL
and S3 requirements. They require JDK 21 and retain evidence under
`build/managed-storage-profile-gate/`. A profile passes only when its current
`result.json` records the repository commit and source/restore resource names.
The gate does not write this file for a dirty worktree.

The server release controller decides which profiles are required from the
server-scoped diff. It may accept passing evidence from an ancestor commit only
when `scripts/server-release-provider-scope` proves that the corresponding
provider implementation and deployment contract are unchanged afterward.

## PlanetScale PostgreSQL

The gate drops and recreates `public` in both supplied databases. Use two
dedicated disposable PostgreSQL 17 databases. Each branch uses its own
`NOSUPERUSER`, `NOBYPASSRLS` application role. Direct endpoints use official
PlanetScale Postgres hosts (`*.horizon.psdb.cloud` or `*.pg.psdb.cloud`) on port
`5432` with `sslmode=verify-full`. The JDBC URL uses the JVM trust store; the
`psql` URLs additionally set `sslrootcert=system`. The logical database is
`postgres` when the PlanetScale CLI returns no database name.

```bash
export SOMEDAY_PLANETSCALE_SOURCE_JDBC_URL=...
export SOMEDAY_PLANETSCALE_RESTORE_JDBC_URL=...
export SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL=...        # username, no password
export SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL=...   # username, no password
export SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL=...  # username, no password
export SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PSQL_URL=... # username, no password
export SOMEDAY_PLANETSCALE_SOURCE_APP_USER=...
export SOMEDAY_PLANETSCALE_SOURCE_APP_PASSWORD=...
export SOMEDAY_PLANETSCALE_RESTORE_APP_USER=...
export SOMEDAY_PLANETSCALE_RESTORE_APP_PASSWORD=...
export SOMEDAY_PLANETSCALE_SOURCE_ADMIN_JDBC_URL=...
export SOMEDAY_PLANETSCALE_SOURCE_ADMIN_USER=...
export SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PASSWORD=...
export SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PASSWORD=...
export SOMEDAY_MANAGED_GATE_ALLOW_RESET=YES
export SOMEDAY_PLANETSCALE_RESET_TARGETS='<source-branch-id>@<host>:5432/<db>,<restore-branch-id>@<host>:5432/<db>'
scripts/managed-storage-profile-gate planetscale
```

Passwords stay out of PostgreSQL command arguments. Restore uses the restore
branch's application role with `--no-owner --no-acl`, then checks every public
relation owner. The gate also checks migrations, RLS, synchronization, Flyway
history, media integrity, and the paired read-only recovery journey.

## Cloudflare R2

Use two new private buckets. Before the gate runs, each bucket needs an
indefinite Bucket Lock rule on `media/v1/`, no expiry rule on that prefix, and
a separate bucket-scoped `Object Read & Write` token. Objects written by this
gate cannot be removed while the indefinite lock remains. Policy inspection is
pinned to Wrangler `4.78.0`.

```bash
export CLOUDFLARE_API_TOKEN=...
export CLOUDFLARE_ACCOUNT_ID=...
export SOMEDAY_R2_SOURCE_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
export SOMEDAY_R2_SOURCE_BUCKET=...
export SOMEDAY_R2_SOURCE_ACCESS_KEY_ID=...
export SOMEDAY_R2_SOURCE_SECRET_ACCESS_KEY=...
export SOMEDAY_R2_RESTORE_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
export SOMEDAY_R2_RESTORE_BUCKET=...
export SOMEDAY_R2_RESTORE_ACCESS_KEY_ID=...
export SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY=...
export SOMEDAY_R2_OFF_PROVIDER_DIR=/absolute/path/to/an/empty/directory
export SOMEDAY_MANAGED_GATE_ALLOW_RESET=YES
export SOMEDAY_R2_RESET_TARGETS='<ACCOUNT_ID>/<source-bucket>,<ACCOUNT_ID>/<restore-bucket>'
scripts/managed-storage-profile-gate r2
```

The Cloudflare token inspects bucket configuration. The two S3
tokens exercise object access. The gate copies source R2 data to the explicit
off-provider directory as ordinary files plus a manifest of SHA-256 digests,
byte counts, and relative keys. Restore verifies that manifest and reapplies the
canonical ciphertext SHA-256 metadata to each object. The gate also proves
cross-bucket read and write access is denied, tests Bucket Lock, restores into
the second bucket, and compares it again after paired-client recovery checks.

A profile is release-verified when its live gate retains passing evidence.
Missing credentials or dedicated resources leave the profile unverified.
