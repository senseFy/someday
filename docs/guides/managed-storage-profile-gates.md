# Managed storage profile gates

These maintainer gates validate named services against Someday's PostgreSQL
and S3 requirements. They require JDK 21 and retain evidence under
`build/managed-storage-profile-gate/`. A profile passes only when its current
`result.json` records the repository commit and source/restore resource names.
The gate does not write this file for a dirty worktree.

Check certification independently of [public image publication](server-release.md):

```bash
scripts/server-release providers X.Y.Z
```

This read-only command checks both profiles and returns nonzero when either
profile lacks valid evidence. It does not run live gates or affect `READY TO TAG`.
`scripts/server-release-provider-scope` compares the candidate with the newest
reachable earlier `server-v*` tag. First, major and minor versions require full
certification; patch versions classify evidence updates from relevant server,
deployment, recovery or dependency changes:

| Change | Profile evidence to refresh |
| --- | --- |
| PostgreSQL persistence, migrations, database wiring, or recovery | PlanetScale |
| Server S3/media adapter, external S3 deployment, or media recovery | R2 |
| Unrelated application, UI, documentation, or release-controller code | Reuse valid evidence |

Passing evidence is read from `build/managed-storage-profile-gate/<profile>/result.json`.
Full certification requires evidence from the candidate commit. For patch
certification, ancestor evidence remains valid only when the scope checker
confirms the implementation and deployment contract are unchanged. An unchanged
scope never substitutes for missing or stale evidence.
Prepare the disposable resources below, refresh evidence as needed, then rerun
`providers`. At least quarterly or when provider behavior is in doubt, use
`SOMEDAY_SERVER_RELEASE_FORCE_MANAGED=all scripts/server-release providers X.Y.Z`
to require full certification of both profiles; the override applies only to
this certification check.

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
history, media integrity, and the paired-client read-only recovery journey. It
does not yet exercise a user recovery code or use the account-current envelope
to restore a fresh client.

## Cloudflare R2

Use two new private buckets. Before the gate runs, each bucket needs an
indefinite Bucket Lock rule on `media/v1/`, no expiry rule on that prefix, and
a separate bucket-scoped `Object Read & Write` token. Objects written by this
gate cannot be removed while the indefinite lock remains. Policy inspection is
pinned to Wrangler `4.78.0`.

The R2 gate builds its MinIO client through `scripts/build-minio-test-image mc`
from pinned, checksum-verified upstream source. Docker and access to the pinned
base images, GitHub source archive and Go modules are required on the first
build; subsequent invocations reuse Docker's build cache.

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

A profile is verified when its live gate retains passing evidence.
Missing credentials or dedicated resources leave the profile unverified.
