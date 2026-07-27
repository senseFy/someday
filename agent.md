# Agent Rules

## Database Evolution

- Client local schema is owned by SQLDelight in `shared/data/src/commonMain/sqldelight/saien/someday/data/local/db/Someday.sq` and its numbered `.sqm` migrations. Do not add platform-specific schema repair or version probing.
- Android, iOS, and Desktop must open local databases with schema-aware SQLDelight drivers. Desktop/JVM creation should go through `createSomedayJdbcDriver(...)`; platform production code must not call `SomedayDatabase.Schema.create`, `SomedayDatabase.Schema.migrate`, read or write `PRAGMA user_version`, or construct a bare `JdbcSqliteDriver(...)`.
- JVM tests that create a local database should use the shared schema-aware factory. A raw driver is only appropriate when reopening an already-created database to verify persisted state without owning schema lifecycle.
- Server schema changes are owned by Flyway files in `server/src/main/resources/db/migration`. Do not patch server schema from application startup or request handling.
- Migrations must be deterministic version-to-version transitions. Do not use `IF EXISTS` or `IF NOT EXISTS` to hide uncertain schema state; model the old state explicitly and migrate it.
- After schema changes, update the SQLDelight schema snapshot and run `./gradlew :shared:data:verifySqlDelightMigration` plus relevant client/server tests. See `docs/database-migrations.md`.

## UI and Main-Thread IO

- Compose composition, route lifecycle callbacks, platform startup on the main thread, and direct UI event handlers must not call repositories, settings stores, credential stores, SQLDelight/JDBC/SQLite APIs, file APIs, WebDAV clients, import/export services, or network clients.
- UI-facing controller actions that touch persistence, secure storage, import/export, sync, or network must be `suspend` and must move only the IO portion into an explicit background dispatcher. Compose state must be updated after that background work returns to the UI coroutine.
- Do not wrap an entire controller action in `withContext(Dispatchers.Default)` from a composable. That can move Compose state writes off the UI thread. The controller/use-case owns the dispatcher boundary.
- Controller constructors and `remember { ... }` blocks must be cheap and side-effect free. Initial repository/settings/credential loading must happen from `LaunchedEffect` or another explicit coroutine lifecycle boundary.
- Derived UI builders such as `buildState` must be pure. Any provider passed into them must be an in-memory UI snapshot, not a repository or store call.
- Platform app startup must show a bootstrap/loading surface while databases, repositories, settings, workspace keys, and credential state are created or loaded off the main thread.
- Tests for UI controllers should call suspend APIs inside coroutine test boundaries and should explicitly call `refresh()` when verifying data loaded from repositories or stores. Do not reintroduce constructor-time loading to make tests pass.

## Sync Protocol

- `docs/sync-system-v2-spec.md` is the product sync contract (workspace-entity DAG + epoch/checkpoint) for WebDAV and self-hosted remotes.
- `docs/workspace-pairing-protocol.md` is the frozen workspace-pairing
  capability, encryption, transport-state, and local-adoption contract.
- Any change to epoch pointers, checkpoints, entity envelopes, validation, encryption metadata, or remote profiles must update that spec in the same change.
- Any change to pairing token parsing, derivation domains, QR payload,
  authority AAD, expiry, envelope fields, remote transitions, or adoption
  ordering must update the pairing protocol and its golden/interoperability
  evidence in the same change.
- Sync changes that touch segment matching, pointer CAS, idempotent replay, or cursor advancement must include JVM protocol tests and relevant client compile checks across iOS, Android, and Desktop.
- `scripts/sync-v2-reliability-gate` and `scripts/sync-v2-test-evidence.tsv` are the current V2-only acceptance gate and evidence map. Keep both green when changing sync behavior.
- Replacing a workspace key via pairing must not run while any key-bound local V2 state exists (preparing/active/blocked/read-only); clear app data first.
- Pairing and first-epoch activation must use the same authority-mutation
  coordinator. Do not introduce a per-service lock or claim before the
  key-bound-state check.
- Pairing secrets are 128-bit random capabilities. Domain-derived identifiers
  are opaque Base64url values; never use a human-entered token or a fast hash
  as a server lookup key, and never log QR/manual-token text.
- WebDAV pairing is append-only creation plus exact-ETag authenticated
  tombstones. It must not issue `DELETE`. Self-hosted pairing must remain
  account-scoped, device-bound, atomic, and without read/delete routes.
- WebDAV V2 folder reset must not call remote DELETE until a DAG-preserving reset exists; the interim implementation fails closed for all V2 resets.
- `someday.systemV2ReleaseEnabled` and `someday.systemV2DevelopmentEnabled` default false; local debug must opt in. Never reintroduce a silent `systemV2ActivationEnabled = true` default.

## Self-hosted Server

- `local` and `production` deployment modes have different explicit security
  contracts. Production startup must remain fail-closed on missing database
  credentials, a strong JWT secret, or an HTTPS public origin.
- Production registration defaults off, admin cookies are `Secure`, browser
  admin mutations require the configured same-origin `Origin`, and proxy
  forwarding headers are ignored unless the operator opts in.
- Authentication rate limiting must remain bounded in memory and apply both
  per-client and per-account budgets. Password inputs and concurrent Argon2
  work must remain bounded; unknown-account login must perform equivalent
  password verification work.
- `compose.yaml` is loopback-only local infrastructure. Do not turn its known
  credentials or exposed dependency ports into a production recipe.
