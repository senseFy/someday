# Agent Rules

## Database Evolution

- Client local schema is owned by SQLDelight in `shared/data/src/commonMain/sqldelight/saien/someday/data/local/db/Someday.sq` and its numbered `.sqm` migrations. Do not add platform-specific schema mutation or version probing.
- Android, iOS, and Desktop must open local databases with schema-aware SQLDelight drivers. Desktop/JVM creation should go through `createSomedayJdbcDriver(...)`; platform production code must not call `SomedayDatabase.Schema.create`, `SomedayDatabase.Schema.migrate`, read or write `PRAGMA user_version`, or construct a bare `JdbcSqliteDriver(...)`.
- JVM tests that create a local database should use the shared schema-aware factory. A raw driver is only appropriate when reopening an already-created database to verify persisted state without owning schema lifecycle.
- Server schema changes are owned by Flyway files in `server/src/main/resources/db/migration`. Do not patch server schema from application startup or request handling.
- Migrations must be deterministic version-to-version transitions. Do not use `IF EXISTS` or `IF NOT EXISTS` to hide uncertain schema state; model the old state explicitly and migrate it.
- After schema changes, update the SQLDelight schema snapshot and run `./gradlew :shared:data:verifySqlDelightMigration` plus relevant client/server tests. See `docs/database-migrations.md`.

## UI and Main-Thread IO

- Compose composition, route lifecycle callbacks, platform startup on the main thread, and direct UI event handlers must not call repositories, settings stores, credential stores, SQLDelight/JDBC/SQLite APIs, file APIs, import/export services, or network clients.
- UI-facing controller actions that touch persistence, secure storage, import/export, sync, or network must be `suspend` and must move only the IO portion into an explicit background dispatcher. Compose state must be updated after that background work returns to the UI coroutine.
- Do not wrap an entire controller action in `withContext(Dispatchers.Default)` from a composable. That can move Compose state writes off the UI thread. The controller/use-case owns the dispatcher boundary.
- Controller constructors and `remember { ... }` blocks must be cheap and side-effect free. Initial repository/settings/credential loading must happen from `LaunchedEffect` or another explicit coroutine lifecycle boundary.
- Derived UI builders such as `buildState` must be pure. Any provider passed into them must be an in-memory UI snapshot, not a repository or store call.
- Platform app startup must show a bootstrap/loading surface while databases, repositories, settings, workspace keys, and credential state are created or loaded off the main thread.
- Tests for UI controllers should call suspend APIs inside coroutine test boundaries and should explicitly call `refresh()` when verifying data loaded from repositories or stores. Do not reintroduce constructor-time loading to make tests pass.

## Sync Protocol

- `docs/sync-system-v3-spec.md` is the product sync contract. The stable
  entity-DAG subsystem remains specified by `docs/sync-system-v2-spec.md`, and
  media wire details by `docs/self-hosted-media-v3.md`.
- `docs/workspace-pairing-protocol.md` is the frozen workspace-pairing
  capability, encryption, transport-state, and local-adoption contract.
- Any change to epoch pointers, checkpoints, entity envelopes, validation,
  encryption metadata, media objects, or authority binding must update the
  relevant V3/subsystem spec in the same change.
- Any change to pairing token parsing, derivation domains, QR payload,
  authority AAD, expiry, envelope fields, remote transitions, or adoption
  ordering must update the pairing protocol and its golden/interoperability
  evidence in the same change.
- Sync changes that touch segment matching, pointer CAS, idempotent replay, or cursor advancement must include JVM protocol tests and relevant client compile checks across iOS, Android, and Desktop.
- `scripts/sync-v3-reliability-gate` is the Linux/PostgreSQL System V3
  self-hosted acceptance gate. `scripts/sync-v3-apple-gate` owns shared
  behavior and app-shell iOS simulator evidence. Keep both and
  `scripts/verify-system-v3-architecture` green when changing synchronization
  behavior. The gates require fresh passing suites but must not pin individual
  JUnit method names.
- Pairing may replace exactly one healthy, unbound, semantically empty local
  draft. It must refuse active, blocked, unhealthy, ambiguous, or non-empty
  local state. Recheck under the shared authority lock and discard the empty
  draft atomically with workspace adoption.
- Pairing and initial workspace authority establishment must use the same
  authority-mutation coordinator. Do not introduce a per-service lock or claim
  before the key-bound-state check.
- Pairing secrets are 128-bit random capabilities. Domain-derived identifiers
  are opaque Base64url values; never use a human-entered token or a fast hash
  as a server lookup key, and never log QR/manual-token text.
- Self-hosted pairing must remain account-scoped, device-bound, atomic, and
  without read/delete routes.
- Product surfaces call the protocol System V3. Frozen internal entity-DAG V2
  identifiers may remain where they are part of the wire contract or internal
  engine names; do not expose them as a selectable product mode.
- The DAG-backed repositories are the product data plane from the first local
  edit, including while network sync is off. Do not add activation flags,
  compatibility routing, or another authoritative note/settings store.
- Every server entity query must explicitly predicate both authenticated user
  id and workspace id. PostgreSQL RLS is defense in depth and must not be
  relied on for isolation because privileged database roles can bypass it.
- Media publication is one immutable encrypted object at
  `/sync/v3/workspaces/{workspaceId}/media/{mediaId}`. Keep the 4 MiB encoded
  original and 12,000,000-pixel static JPEG/PNG/WebP bounds consistent across
  domain validation, local persistence, wire capabilities, and server routes.
- Local media publication evidence is the atomic tuple of account authority,
  workspace id, and ciphertext digest. Server object identity and blob keys are
  account/workspace scoped; quota remains an account-wide total across
  workspaces.

## Self-hosted Server

- `docs/server-storage-architecture.md` is the canonical server persistence and
  deployment-topology decision.
- `local` and `production` runtime security modes, selected by
  `SOMEDAY_DEPLOYMENT_MODE`, have different explicit contracts. Production
  startup must remain fail-closed on missing database credentials, a strong JWT
  secret, or an HTTPS public origin.
- Runtime security mode is independent of storage topology. Support standalone
  PostgreSQL plus filesystem media and the recommended external PostgreSQL plus
  S3-compatible media topology without adding a provider plugin system. The
  external application container must own no durable user data.
- Production media configuration has exactly one explicit `filesystem|s3`
  backend choice; local development may default to filesystem. Do not add
  silent fallback, vendor-specific client behavior, PostgreSQL BLOB media,
  public object URLs, or S3-mounted filesystem emulation.
- Blob publication precedes PostgreSQL metadata. Preserve immutable exact
  replay, retain safe untracked blobs after a database failure, and give the
  first-release application runtime no list/delete operations, no
  `DeleteObject` permission, and no compensation state machine. An AWS runtime
  policy may grant `ListBucket` only for `media/v1/*` so missing HEAD/GET is
  distinguishable from permission denial. Exact-replay adoption must hash the
  actual existing bytes rather than trusting object metadata.
- Production registration defaults off, admin cookies are `Secure`, browser
  admin mutations require the configured same-origin `Origin`, and proxy
  forwarding headers are ignored unless the operator opts in.
- Authentication rate limiting must remain bounded in memory and apply both
  per-client and per-account budgets. Password inputs and concurrent Argon2
  work must remain bounded; unknown-account login must perform equivalent
  password verification work.
- `compose.yaml` is loopback-only local infrastructure. Do not turn its known
  credentials or exposed dependency ports into a production recipe.
- Portable export/restore omits image bytes. Operator backup and recovery of
  published media must treat PostgreSQL and the configured media blob store as
  one recovery unit. Standalone needs coordinated off-host directory backups;
  external needs PostgreSQL recovery points plus bucket versioning/retention.
