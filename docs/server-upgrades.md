# Server upgrade and rollback

Server releases are forward-only. Within one major version, an existing
Compose file and `.env` remain valid when only `SOMEDAY_IMAGE` changes.

## Upgrade

1. Read the target release notes.
2. Capture and test the PostgreSQL-plus-media recovery unit.
3. Record the current tag-and-digest image reference.
4. Set the new tag-and-digest image reference in `.env`.
5. Pull and recreate only the app container.

```bash
docker compose pull server
docker compose up -d server
docker compose ps
curl --fail http://127.0.0.1:3180/health
```

Then run `verify-media-integrity`, sign in to `/admin`, and verify text and an
image from a paired client.

From the second server release onward, release acceptance includes an actual
upgrade of non-empty data from the immediately preceding version.

## Failed upgrade

Stop the new server before deciding how to recover. If its migration did not
start, the previous image digest may be restarted. If a newer Flyway migration
was applied, do not run the old image against that database.

Rollback after a schema change means restoring the complete pre-upgrade
PostgreSQL and media recovery unit, then restoring the previous image digest:

```bash
# Restore the pre-upgrade database and media into isolated storage first.
# After validation, point Compose at that recovery unit and set:
SOMEDAY_IMAGE=ghcr.io/sensefy/someday-server:<previous-version>@sha256:<previous-digest>
docker compose pull server
docker compose up -d server
```

An old image must refuse a database containing unknown future migrations. Do
not edit `flyway_schema_history`, downgrade tables manually, or replace a
published image under the same tag.
