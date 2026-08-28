# Server backup and recovery

PostgreSQL and media are one recovery unit. A usable backup needs both, plus
the stable JWT secret and storage configuration.

Portable client export is separate: it contains no image bytes or workspace
keys and cannot rebuild a server.

## Before storing real data

- Choose an off-host destination for database and media backups.
- Record the exact server version and keep the matching Compose files.
- Back up the JWT secret separately from the data copy.
- Keep at least one paired device intact; only clients hold the workspace key.
- Restore into an isolated database and media location.
- Run `verify-media-integrity` and complete a paired-client read check before
  allowing any client write.

## Standalone backup

From the repository checkout or extracted deployment bundle root, quiesce the
app so PostgreSQL and media describe the same point in time:

```bash
cd deploy/standalone
docker compose stop server
mkdir -p backup/media
chmod 700 backup
docker compose exec -T postgres sh -c \
  'pg_dump --username postgres --dbname "$POSTGRES_DB" --format custom' \
  > backup/postgresql.dump
docker compose cp server:/var/lib/someday/media/. backup/media
docker compose start server
cd ../..
```

Copy `backup/`, the exact `.env`, and the release version to protected off-host
storage. A Docker volume by itself is not a backup.

## Isolated restore

Use a different Compose project, database volume, media volume, and HTTP port.
Do not point clients at it yet.

```bash
cp -R deploy/standalone someday-restore
cd someday-restore
# Copy the recovery .env, then set SOMEDAY_HTTP_PORT to an unused loopback port.
docker compose -p someday-restore up -d postgres
docker compose -p someday-restore exec -T postgres sh -c \
  'dropdb --username postgres --force "$POSTGRES_DB" && \
   createdb --username postgres --owner "$SOMEDAY_DB_USER" "$POSTGRES_DB"'
docker compose -p someday-restore exec -T postgres sh -c \
  'pg_restore --username postgres --dbname "$POSTGRES_DB" --no-acl --exit-on-error' \
  < backup/postgresql.dump
docker compose -p someday-restore run --rm --no-deps \
  --user 0:0 \
  --volume "$PWD/backup/media:/backup:ro" \
  --entrypoint /bin/sh \
  server -ec \
  'cp -R /backup/. /var/lib/someday/media/ && \
   chown -R 10001:10001 /var/lib/someday/media'
```

Validate before starting normal service traffic:

```bash
docker compose -p someday-restore run --rm --no-deps \
  server verify-media-integrity
```

Exit status `0` means every PostgreSQL media record matched the actual bounded
object bytes. Status `2` means at least one referenced object is missing or
divergent. Status `1` means verification could not complete.

Also compare the restored database owner, public relation owners, and
`flyway_schema_history` with the source. Row and media counts must be non-zero
for a meaningful exercise.

## Paired-client verification

Keep the restore isolated and reject content writes at the temporary ingress:

- allow GET and HEAD reads;
- allow only the entity POST reads `checkpoint/fetch`, `pull`, and
  `frontiers`; and
- reject media PUT and every entity mutation endpoint.

Using an intact paired device with no local copy of the test content, pull one
non-empty note and materialize one image. Confirm that an attempted entity push
and media upload are rejected. Only then remove the temporary write block and
make the restored server authoritative.

The automated equivalent is:

```bash
scripts/server-recovery-gate
```

It uses ordinary `pg_dump`, `pg_restore`, and a media archive. It adds no
backup format or scheduler.

## External storage

Use provider-native PostgreSQL recovery points and bucket retention, but keep
portable logical dumps and an off-provider media copy when provider/account
loss is in scope. Restore both into isolated resources and apply the same
integrity and paired-client checks.

When restoring plain files to S3, verify their actual SHA-256 digests and set
`someday-ciphertext-sha256=sha256:<digest>` on every uploaded object.

An object-store superset is valid: unreferenced immutable objects are harmless.
Every PostgreSQL media row must still have the exact expected object key,
length, and actual-byte SHA-256.
