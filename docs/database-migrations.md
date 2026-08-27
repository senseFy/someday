# Database Migrations

Someday has two database surfaces:

- Client local data uses SQLDelight in `shared:data`.
- The self-hosted server uses Flyway migrations under `server`.

Both surfaces must evolve through versioned migrations. Platform entry points and feature repositories must not recreate, mutate, or inspect schema versions themselves.

## Client Local Database

The local schema is owned by SQLDelight files under `shared/data/src/commonMain/sqldelight/saien/someday/data/local/db`.

The pre-release repository currently has one squashed System V3 snapshot,
`databases/1.db`, and no `.sqm` files. Existing development installations must
clear local app data when adopting this baseline. After the first public
release, schema history is immutable and every change starts the normal
numbered migration sequence described below.

Notes, notebooks, deletions, and synchronized workspace preferences have one
durable local truth: `workspace_entity_versions_v2` plus its parent/head and
typed projection tables. There are intentionally no parallel `notes`,
`notebooks`, `note_versions`, `tombstones`, `locations`, or `sync_metadata`
tables. The small data-layer repository owns only device-local settings,
installation/workspace metadata, and the database handle used by the typed DAG
store.

The first-release sync lifecycle persists only one generation's checkpoint,
control objects, entity DAG, outbox/cursors, portable source-import mappings,
and unresolved remote evidence. It intentionally has no durable transport-unit
packing table or restored-backup reconciliation flag. Checkpoint states are
`preparing`, `published`, and `active`; control-object states are `prepared`,
`published`, and `active`; portable source imports are `committed` or
`published`.

The squashed client media table records publication evidence atomically as
`published_authority_binding_id`, `published_workspace_id`, and
`published_object_digest`. All three are absent or present together. Evidence
is valid only for that authenticated account and workspace; switching either
scope makes the asset pending there without introducing another state machine.
The same schema enforces the initial 4 MiB encoded-image and 12,000,000-pixel
bounds.

When changing local tables, columns, indexes, or constraints:

1. Update `Someday.sq` to describe the latest schema and queries.
2. Add the next numbered `.sqm` migration for the old-version to new-version transition.
3. Regenerate or update SQLDelight schema snapshots.
4. Run `./gradlew :shared:data:verifySqlDelightMigration`.
5. Use schema-aware drivers at app and test entry points.

Platform modules may construct or provide a `SqlDriver`, but must not call `SomedayDatabase.Schema.create`, `SomedayDatabase.Schema.migrate`, or manually read/write `PRAGMA user_version`.

JVM and Desktop code should use `createSomedayJdbcDriver(...)` from `shared:data` when opening a local database that may need creation or migration. Tests that create a fresh local database should use the same factory so migration behavior is exercised consistently.

SQLDelight migrations should be deterministic version-to-version transitions. Do not use conditional DDL such as `IF EXISTS` or `IF NOT EXISTS` to hide unknown schema state. If an old shipped schema had a real defect, model that old state explicitly and migrate from it in the shared SQLDelight migration chain.

## Server Database

Server schema is owned by Flyway files under `server/src/main/resources/db/migration`.

The first server release supports PostgreSQL 17. Both the normal server and
`bootstrap-admin` validate the same immutable migration set, reject an unknown
future migration, and check the exact Someday RLS table/policy catalog before
serving or mutating data.

This pre-release branch rewrites unpublished sync migration history around the
System V3 server schema. Existing development server databases carrying the
earlier history must be recreated before running this branch. After the first
public release, the retained Flyway history is immutable.

Entity workspace registry/data and media metadata use forced PostgreSQL RLS
bound to both `someday.user_id` and `someday.workspace_id`. Repositories still
carry explicit `(user_id, workspace_id)` predicates; RLS is defense in depth,
not a replacement for scoped SQL. Account-wide media quota temporarily selects
only the authenticated account with a workspace wildcard while holding its
transaction advisory lock, then restores the exact workspace scope.

`V7__workspace_pairing_invites.sql` adds the self-hosted pairing state
machine. Its composite `(user_id, invite_id)` primary key enforces account
scope; database checks constrain available, claimed, completed, and cancelled
records so ciphertext and claim identity exist only in valid states.

`V8__system_v3_media_metadata.sql` adds one immutable media-object record keyed
by `(user_id, workspace_id, media_id)`, with a foreign key to the workspace
registry. Ciphertext bytes remain in the configured media blob store; account
quota is summed across workspaces. Operators must back up PostgreSQL and that
store as one recovery unit. The standalone topology uses a filesystem store;
the external topology uses an S3-compatible store. Backend choice
does not alter Flyway schema or create provider-specific database migrations;
see `server-storage-architecture.md`.

When changing server tables, columns, indexes, or constraints:

1. Add the next immutable `V#__description.sql` migration.
2. Do not edit already-applied migrations in a deployed environment; add a new migration instead.
3. Keep migrations deterministic. Avoid `IF EXISTS` and `IF NOT EXISTS` in versioned DDL.
4. Run `./gradlew :server:test` and the relevant integration checks.

Keep tenant-row DML and any RLS wildcard it requires inside the same
transactional migration. A non-transactional migration must not modify tenant
rows. Verify its result with the non-empty previous-release upgrade gate; a
partial SQL parser is not an acceptance test.

Flyway is the only server schema evolution path. Application startup and request handling should not patch database shape.

## Enforcement

`GradleTopologyTest` guards these boundaries:

- Platform production code cannot own local schema migration.
- JVM tests cannot bypass shared schema-aware database factories with direct SQLDelight schema lifecycle calls.
- SQLDelight and Flyway migrations cannot use conditional DDL as a substitute for a clear migration path.
