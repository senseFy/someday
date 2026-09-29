# Database Migrations

Someday has two database surfaces:

- Client local data uses SQLDelight in `shared:data`.
- Someday Server uses Flyway migrations under `server`.

Both surfaces evolve through versioned migrations. Platform entry points and
feature repositories leave schema management to SQLDelight or Flyway.

## Client Local Database

The local schema is owned by SQLDelight files under `shared/data/src/commonMain/sqldelight/saien/someday/data/local/db`.

The squashed baseline phase has ended. `databases/1.db`, `1.sqm`, and
`databases/2.db` are frozen. `1.sqm` upgrades installed schema 1 to schema 2;
`2.sqm` upgrades schema 2 to schema 3, recorded in `databases/3.db`. Development
installations created before the schema-1 baseline must still clear local app
data; schema-1 and schema-2 installations upgrade in place. Retain numbered
migrations and their snapshots for all subsequent changes.

Schema 2 adds account incarnation to the published local workspace authority,
backfilling every existing binding to G0 without changing its workspace or
writer. Separate installation tables retain reset intent, workspace network
gates, and monotonic endpoint/user protocol-1 evidence across workspace
replacement. Reset submission persists uncertainty before sending the request.
The media publication proof remains the existing authority/workspace/digest
tuple; this migration neither rebuilds `media_assets` nor changes its data.

Schema 3 adds nullable `discard_target_incarnation` to workspace gates. It
records explicit replacement consent for a discovered current incarnation,
including devices that did not initiate the reset. Existing gates receive no
consent; an upgrade cannot grant permission to discard a local workspace.

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

The shared JVM factory checks the stored version, creates or migrates, and
updates `user_version` inside one driver transaction. It rejects a database
newer than its supported schema before exposing the connection to repositories.
SQLDelight 2.1.0's default schema-taking JDBC constructor does not reject newer
schemas, so existing older binaries cannot acquire this protection retroactively.
Opening a newer schema with an older released Desktop binary remains a
separate release-compatibility acceptance check.

`verifySqlDelightMigration` keeps the public Gradle gate name but now runs
`SomedayDatabaseMigrationTest`: copy the frozen schema-1 and schema-2 snapshots,
seed all 23 original tables and all 26 schema-2 tables respectively with valid
related data, stamp the installed version, and open each through the shared
driver. The tests compare every old column and value, G0 backfill, preservation
of pending reset intent and network gates, and absence of implicit discard
consent. They compare `sqlite_master`, columns, foreign keys, and indexes against
a newly created database and the schema-3 snapshot. They also check repeated
opens and future-schema refusal without modifying the file. SQLDelight snapshots carry
their version in the filename; the fixture sets installation `user_version`
explicitly rather than opening the empty snapshot as an uninitialized file.

Native `verifyMigrations` remains disabled because SQLDelight 2.1.0's
`ObjectDiffer` does not terminate on this schema's foreign-key graph. The real
shared-driver test replaces that verifier; the retained
`scripts/verify-sqldelight-v2-baseline` entry point now verifies frozen history,
executes `1.sqm` and `2.sqm`, and compares both intermediate and final
migrated/fresh/snapshot catalogs. It no longer
rejects numbered migrations or requires a single snapshot.

SQLDelight migrations should be deterministic version-to-version transitions. Do not use conditional DDL such as `IF EXISTS` or `IF NOT EXISTS` to hide unknown schema state. If an old shipped schema had a real defect, model that old state explicitly and migrate from it in the shared SQLDelight migration chain.

## Server Database

Server schema is owned by Flyway files under `server/src/main/resources/db/migration`.

The first server release supports PostgreSQL 17. Both the normal server and
`bootstrap-admin` validate the same immutable migration set, reject an unknown
future migration, and check the exact Someday RLS table/policy catalog before
serving or mutating data.

The published server migration history through V9 is immutable. Development
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
quota is summed across workspaces of the active account incarnation. Operators must back up PostgreSQL and that
store as one recovery unit. The standalone topology uses a filesystem store;
the external topology uses an S3-compatible store. Backend choice
does not alter Flyway schema or create provider-specific database migrations;
see `server-storage-architecture.md`.

`V9__workspace_recovery_envelopes.sql` adds one account-current opaque recovery
envelope keyed by `user_id`. The row selects one already initialized
`workspace_id`, stores its key fingerprint, exact bounded envelope JSON and
digest, positive CAS revision, creating/updating device identities, and
timestamps. The server never stores the user recovery code or plaintext
workspace key. Account deletion cascades to the envelope; workspace deletion
does the same through the account/workspace foreign key. Exact repository
queries still predicate the authenticated user explicitly.

`V10__account_data_incarnations.sql` installs account incarnation admission
metadata and committed-receipt storage. It preserves existing rows as G0, adds
account-owned device foreign keys, and creates G0 atomically through a user
insert trigger. New incarnation/receipt tables follow authentication-table
conventions without RLS; existing forced workspace policies remain unchanged.
`AccountIncarnationMigrationIntegrationTest` creates a separate database,
applies V9, seeds nonempty account/entity/media/pairing/recovery data, applies
V10, and verifies unchanged legacy values, backfills and constraints. It also
checks rollback on invalid legacy ownership and the account-creation trigger.
Run it with the existing PostgreSQL integration suite and test-only admin
credentials that can create/drop its disposable databases.

`V11__retired_media_maintenance_context.sql` adds a database verification epoch,
per-incarnation audit IDs and persistent reclamation/revalidation evidence.
Restore rotates that context before any reset or maintenance resumes. These are
operator verification records, not a pending-reset state machine.

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
