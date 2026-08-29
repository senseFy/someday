# Database Migrations

Someday has two database surfaces:

- Client local data uses SQLDelight in `shared:data`.
- The self-hosted server uses Flyway migrations under `server`.

Both surfaces evolve through versioned migrations. Platform entry points and
feature repositories leave schema management to SQLDelight or Flyway.

## Client Local Database

The local schema is owned by SQLDelight files under `shared/data/src/commonMain/sqldelight/saien/someday/data/local/db`.

The client currently has one squashed System V3 snapshot, `databases/1.db`, and
no `.sqm` files. Development installations created before this baseline must
clear local app data. Once a client release ships the snapshot, later changes
use numbered migrations and retain its history.

`workspace_entity_versions_v2`, its parent/head tables, and typed projections
store notes, notebooks, deletions, and synchronized workspace preferences.
Device-local settings and installation metadata stay in their dedicated
tables.

The current sync lifecycle persists one generation's checkpoint, control
objects, entity DAG, outbox/cursors, portable source-import mappings, and
unresolved remote evidence. Checkpoint states are
`preparing`, `published`, and `active`; control-object states are `prepared`,
`published`, and `active`; portable source imports are `committed` or
`published`.

The squashed client media table records publication evidence atomically as
`published_authority_binding_id`, `published_workspace_id`, and
`published_object_digest`. All three are absent or present together. Evidence
is valid only for that authenticated account and workspace; switching either
scope makes the asset pending there.
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

The published server migration history through V8 is immutable. Development
databases created from earlier unpublished migrations must be recreated rather
than patched.

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
rows. Verify the result with the non-empty previous-release upgrade gate.

Use Flyway for every server schema change. Application startup and request
handling do not patch database shape.

## Enforcement

`GradleTopologyTest` guards these boundaries:

- Platform production code cannot own local schema migration.
- JVM tests cannot bypass shared schema-aware database factories with direct SQLDelight schema lifecycle calls.
- SQLDelight and Flyway migrations cannot use conditional DDL as a substitute for a clear migration path.
