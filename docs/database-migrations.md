# Database Migrations

Someday has two database surfaces:

- Client local data uses SQLDelight in `shared:data`.
- The self-hosted server uses Flyway migrations under `server`.

Both surfaces must evolve through versioned migrations. Platform entry points and feature repositories must not repair, recreate, or inspect schema versions themselves.

## Client Local Database

The local schema is owned by SQLDelight files under `shared/data/src/commonMain/sqldelight/saien/someday/data/local/db`.

The pre-release repository currently has one squashed V2-only snapshot,
`databases/1.db`, and no `.sqm` files. Existing development installations must
clear local app data when adopting this baseline. After the first public
release, schema history is immutable and every change starts the normal
numbered migration sequence described below.

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

This pre-release branch rewrites unpublished sync migration history around the
V2-only server schema. Existing development server databases carrying the
earlier history must be recreated before running this branch. After the first
public release, the retained Flyway history is immutable.

`V7__workspace_pairing_invites.sql` adds the self-hosted pairing state
machine. Its composite `(user_id, invite_id)` primary key enforces account
scope; database checks constrain available, claimed, completed, and cancelled
records so ciphertext and claim identity exist only in valid states.

When changing server tables, columns, indexes, or constraints:

1. Add the next immutable `V#__description.sql` migration.
2. Do not edit already-applied migrations in a deployed environment; add a new migration instead.
3. Keep migrations deterministic. Avoid `IF EXISTS` and `IF NOT EXISTS` in versioned DDL.
4. Run `./gradlew :server:test` and the relevant integration checks.

Flyway is the only server schema evolution path. Application startup and request handling should not patch database shape.

## Enforcement

`GradleTopologyTest` guards these boundaries:

- Platform production code cannot own local schema migration.
- JVM tests cannot bypass shared schema-aware database factories with direct SQLDelight schema lifecycle calls.
- SQLDelight and Flyway migrations cannot use conditional DDL as a substitute for a clear migration path.
