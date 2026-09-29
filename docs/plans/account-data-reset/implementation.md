# Account Data Reset — implementation and review plan

Status: **accepted** (updated 2026-09-29) — implementation and local acceptance.
The READ COMMITTED prerequisite and Stage A are implemented and passed their
automated acceptance gates. B1 logical reset and B2 operator maintenance are
implemented and passed automated acceptance. Stage C shared client transport,
durable state and migration are implemented and passed automated acceptance.
Stage D is implemented and passed local cross-platform acceptance;
Stage E local verification is recorded below; provider certification, released
client evidence and coordinated rollout remain pending. The overall plan is not
completed. Reset defaults off.
No production migration, deployment or real-data operation has been performed.

Scope: implementation ownership, coordinated documentation amendments, delivery
order and acceptance evidence. The design alone owns the proposed behavior and
product decisions; this document alone owns the failure/test matrix.

## 1. Source evidence and document lifecycle

Follow the [documentation rules](../../README.md). Design approval changes plans
to `accepted`, not implemented. Update current specs/guides with each implemented
behavior, never merely because this plan was accepted. Publish the lasting reset
contract in `docs/specs/account-data-reset-protocol.md` when implemented, instead
of maintaining a competing copy here. After implementation and acceptance gates,
mark both plans `completed`, move them together to
`docs/archive/account-data-reset/`, link successors and update the index.

### Source evidence

This table records the pre-implementation baseline; completed changes are
described by current specs and the delivery status below.

| Existing source | Fact relevant to implementation |
| --- | --- |
| `server/.../routes/RouteSupport.kt`, `AuthRoutes.kt`, `DeviceRoutes.kt` | Route JWT/session checks are not atomic with later repository writes. Password login and device registration are separate; registration requires `devices` scope. |
| `server/.../persistence/AuthRepository.kt` | Device UUIDs are globally claimed; manual `revoked_at` prevents reuse. Registration revokes prior sessions for that device. Pairing transitions and the active-invitation count live here; that count currently filters only user/state/expiry. Eight is the active-invitation limit, not a total-row bound: completed/cancelled rows remain, and bulk expiry cleanup runs on later create calls. |
| `server/.../persistence/SyncV2Repository.kt` | `selectWorkspaceScope` inserts the registry row before existing workspace locks. Entity transactions, registry creation, immutable replay and genesis CAS need outer incarnation admission before that insertion. |
| `server/.../persistence/SystemV3MediaRepository.kt` | `putObject` also calls `ensureWorkspace`; media can register a workspace before entity initialization. Blob publication runs inside the quota transaction; reads release the connection before blob IO. |
| `server/.../persistence/WorkspaceRecoveryAccountLock.kt`, `WorkspaceRecoveryEnvelopeRepository.kt` | Existing recovery/first-epoch serialization is narrower than account reset; these helpers already force `READ COMMITTED`. |
| `server/.../persistence/DatabaseConnections.kt`, auth/media transaction helpers | Neither the pool nor those two helpers pins isolation. A database default of `REPEATABLE READ` can establish a snapshot before a quota/account lock. |
| `server/.../persistence/AdminRepository.kt`, `routes/RouteSupport.kt` | Mutations target one account/session/device. Their authenticated administrator can belong to a different account. `requireAdmin` uses ordinary session-backed authentication, including admin cookies. No batch-admin feature exists. Existing admin connections use account/workspace wildcard RLS scope; the plan must distinguish this authorized administration path from ordinary account requests. |
| `server/.../media/MediaBlobStore.kt`, `S3MediaBlobStore.kt`, `MediaBlobStoreStartupProbe.kt` | Runtime immutable PUT/HEAD/GET has no list/delete API. Filesystem publication is synchronous; S3's 30-second call/10-second attempt timeouts do not prove remote cancellation. Failed PUT transactions leave no durable attempt ledger. The startup probe currently covers only `media/v1/`. Concurrent PUTs can hold shared admission while waiting for the existing quota lock, so one S3 call timeout does not bound total account-drain time. |
| `shared/sync/.../selfhosted/KtorSelfHostedSyncTransport.kt`, `shared/sync/src/jvmMain/kotlin/saien/someday/sync/selfhosted/JdkSelfHostedSyncTransport.kt` | Two strict-JSON HTTP implementations. Android/iOS use Ktor; Desktop explicitly selects JDK. Both need headers, typed failures and real transport tests. Several entity/media routes accept 409 as a business DTO; the new typed header must be processed before that route decoder. |
| `shared/sync/.../selfhosted/SelfHostedSyncClient.kt`, `SelfHostedSyncRemoteV2.kt` | HTTP exceptions lack typed error codes; the session executor retries every 401 today. |
| `shared/sync/.../selfhosted/SelfHostedSetupService.kt`, `SelfHostedSyncClient.kt` | Bound password renewal calls `loginAndReconnectBound`, then registration after checking user identity. It needs an incarnation check before registration, separate from consented rejoin. |
| `shared/sync/.../selfhosted/ActiveWorkspaceSessionGuard.kt` | Current publication requirement binds account, workspace and stable writer, but not account incarnation. |
| `shared/sync/.../selfhosted/SelfHostedConnectionSwitchService.kt` | Existing key staging and workspace replacement preserve `localDeviceId`; changing workspace alone does not require writer rotation. |
| `shared/sync/.../WorkspaceLifecycleCoordinator.kt`, `causality/v2/WorkspaceLocalReplacementV2.kt`, `shared/data/.../crypto/WorkspaceKeyRepository.kt` | Shared lifecycle coordination, transactional replacement and secure-alias staging are reuse boundaries. |
| `shared/sync/.../SystemV3ClientServices.kt`, Android `SomedayApplication.kt`, platform `*NotesRepository.kt` | The graph captures a stable writer. Keeping it avoids replaceable graph/identity ownership work, but stale workspace/incarnation callbacks still need guards. |
| `shared/domain/.../settings/ClientSettings.kt` | Secure credentials use a versioned Base64 envelope, requiring an explicit encoding revision. |
| `shared/data/src/jvmMain/kotlin/saien/someday/data/local/SomedayJdbcDriverFactory.kt` | Delegates to SQLDelight 2.1.0, which does not reject newer schemas. A new guard cannot protect an already-released Desktop executable. |
| `shared/data/src/commonMain/sqldelight/saien/someday/data/local/db/Someday.sq` | Baseline schema is 1, with no numbered migration yet. Incarnation bindings/intents still require a migration even without identity relocation. |
| `shared/data/build.gradle.kts`, `scripts/verify-sqldelight-v2-baseline` | The custom verifier rejects numbered migrations and multiple snapshots. SQLDelight 2.1.0 ObjectDiffer does not terminate on this schema's FK graph, so native migration verification is disabled; the first migration needs a real replacement gate. |
| `server/.../persistence/DatabaseMigrator.kt`, `AdminBootstrap.kt` | Current RLS catalog permits only account/workspace policies. Bootstrap already uses the common auth account-creation path; direct-SQL fixtures also create users. |
| `scripts/verify-system-v3-architecture` | The gate matches the literal `loadEpoch(userId: UUID, workspaceId: String)` signature; update it with authenticated-context admission while retaining explicit scoped-SQL and isolation evidence. |
| `server/src/main/resources/db/migration/` | Released V1–V9 are frozen; account/entity/pairing/media/recovery FKs constrain cleanup order. |

`server/...` abbreviates `server/src/main/kotlin/saien/someday/server`;
`shared/<module>/...` abbreviates that module's
`src/commonMain/kotlin/saien/someday/<module>`, unless a source set is explicit.

## 2. Coordinated specification and guide amendments

These are required at implementation, not claims about the current product.
Link to the owning reset spec rather than copying its complete protocol into
each subsystem document.

| Owner | Amendment when implemented | Preserve |
| --- | --- | --- |
| New `docs/specs/account-data-reset-protocol.md` | Incarnation, HTTP/idempotency, re-enrollment, local replacement, unknown-outcome exit, storage gate and rollback-limit contract from the reviewed design. | No authorization for real-data operations. |
| [System V3](../../specs/sync-system-v3-spec.md) | Outer account incarnation, renewal versus consented rejoin, mismatch handling, quota across the active incarnation's workspaces, logical versus physical completion. | System V3 name and one local DAG data plane; local generation terminology. |
| [Entity subsystem](../../specs/sync-system-v2-spec.md) | Admission before registry creation/replay; retired workspace refusal; reset-required versus corruption/epoch rollback. | Canonical envelopes, objects, DAG, checkpoints and epoch CAS. |
| [Pairing](../../specs/workspace-pairing-protocol.md) | Incarnation-scoped transitions and counts, same-device re-enrollment, one local replacement and captured-incarnation install checks. | Stable installation identity, token/QR/KDF/AAD/payload and per-attempt discard confirmation. |
| [Recovery](../../specs/workspace-recovery-protocol.md) | Reset's account-current-envelope removal, incarnation-bound GET/PUT/replay and revision scope, typed-error precedence over CAS conflicts. | No standalone DELETE; code/KDF/AAD/envelope and prepare-confirm-publish rules. |
| [Media V3](../../specs/self-hosted-media-v3.md) | Both registry creators, active-incarnation quota, private paths, retired-media gate and operator cleanup. | Public routes, immutable media-first publication, unchanged local authority/workspace/digest proof, 4 MiB/12 MP bounds and cryptography. |
| [Storage architecture](../../specs/server-storage-architecture.md) | Nested incarnation namespace, actual nested-path probe, optional operator command for SQL/blob cleanup, conditional reclamation evidence and retention-dependent repeat reset. | Two topologies, stateless external app, no provider plugins or runtime blob delete/list. |
| [Database migrations](../../guides/database-migrations.md) | End the squashed-baseline phase: frozen `1.db`, numbered migration and real upgrade gate. Add G0 backfill/creation trigger, single active-incarnation authority, auth-style account-only tables, mutable device versus immutable session, local binding/intent/gate migration and shared JVM schema refusal. | SQLDelight/Flyway ownership, existing workspace RLS, unchanged writer storage and frozen released migrations. |
| [Backup and recovery](../../guides/server-backup-and-recovery.md) | Explicit reset/receipt/authorization rollback risk; invalidate cleanup plans/attestations after restore; preserve mismatched local copies and offer export before explicit discard. Same-incarnation restore can remain undetected. | Coordinated DB/media recovery, no collection of user recovery codes; no v1 rollback-history detector. |
| [Test strategy](../../guides/sync-system-v3-test-strategy.md) | Reset races at repository, transport and client boundaries; stable-device rejoin, migration and restore-limit evidence. | Fresh existing gates, no method-name pinning or sleep-based race synchronization. |
| [Self-hosting](../../guides/self-hosting.md), [standalone](../../guides/self-hosting-standalone.md), [external](../../guides/self-hosting-external.md) | `SOMEDAY_ACCOUNT_RESET_ENABLED`, operator policy attestation versus read/write probes, startup checks after first reset even when disabled, optional maintenance and retention/settling assumptions. | Production fail-closed security, loopback local compose and active-object protection. |
| [Managed storage gates](../../guides/managed-storage-profile-gates.md) | Distinguish first logical-reset readiness from optional reclamation capability. Verify nested-path policy exceptions and document the S3 settling/listing assumption separately. | Existing `media/v1/*` scope and indefinite R2 lock; no purge guarantee from read/upload certification. |
| [Server upgrades](../../guides/server-upgrades.md), [server release](../../guides/server-release.md) | Explicitly disabled rollout/templates, strict enablement parsing, schema downgrade refusal and operator checklist. | Publishing, migration and deployment require their own approval. |
| [Client release](../../guides/client-release.md) | All three clients, minimum protocol behavior after reset, profile downgrade limits and old-binary evidence. | Legacy-server compatibility, no implied backward compatibility of migrated local files. |

Update root `agent.md` only alongside implementation: name account incarnation
separately from local generation, specify quota across all workspaces of the
active incarnation, preserve the media proof's authority/workspace/digest tuple,
and describe the nested namespace and separate maintenance boundary. Runtime
permissions remain within `media/v1/*`; check explicit nested-path exceptions
rather than demanding another S3 permission prefix. Do not introduce a
control-journal namespace or runtime compensation deletes.

## 3. Delivery stages and code ownership

### Prerequisite — isolate the existing transaction bug

Implemented and regression-verified before Stage A: authentication and media
transactions force `READ COMMITTED` before any scope SQL/read/lock statement,
matching entity/recovery helpers. Stage A consolidates this guarantee in the
shared account transaction helper. The three real PostgreSQL regression cases
failed against the prior behavior and passed with explicit isolation.

Verify with PostgreSQL defaulting to `REPEATABLE READ`: two media uploads each
fit alone but exceed remaining quota together. Pause the second after its scope
setup and before quota acquisition; let the first commit. It must see the new
total and reject. Assert actual stored bytes/metadata, not just absence of an
exception. Include auth refresh/registration transaction coverage.

### Stage A — schema and universal admission, reset disabled

Implemented and verified on 2026-09-28. The current contract is
[account incarnation admission](../../specs/account-data-reset-protocol.md).
Evidence includes a nonempty V9→V10 upgrade and failed-upgrade rollback, real
PostgreSQL admission/refresh/quota races, both blob layouts and actual nested
startup readiness, unchanged client protocol journeys, and the fresh reliability
and Apple gates. The Stage A delivery did not expose reset POST; B1 below adds
that operation. The checks below describe the implemented Stage A scope.

- Add the narrow account transaction/lock boundary under server persistence.
  Apply the full route/lock/admission matrix in design section 5.1. Only reset
  and purge-marker changes take an exclusive account lock; login, refresh,
  registration/re-enrollment, logout, revocation, ordinary content and admin
  operations take shared account locks plus their existing row/workspace locks.
  Include `/me`, `/devices`, capabilities and all control routes explicitly.
  Keep data query ownership in existing repositories.
- Apply account-lock wait budgets of 250 ms shared and 45 s exclusive as
  transaction-local lock timeouts on the account-lock statement only. The
  45 seconds is a maximum wait before rollback, not a promise that uploads will
  drain in that time: multiple PUTs may wait on quota while holding admission,
  and immutable S3 replay can perform PUT then GET with separate 30-second
  call deadlines.
  Emit `503 account_busy` only after confirmed rollback of that attempt, never
  for unknown COMMIT/rollback outcomes. Registration alone must not introduce an
  exclusive-lock outage for untouched G0 accounts.
- Extend `AuthRepository`, `RouteSupport`, `AuthRoutes`, `DeviceRoutes` and admin
  actor/target operations. Capture issuing `accountIncarnation` in the session
  transaction and expose `X-Someday-Account-Incarnation`; keep JWT payloads and
  existing successful JSON field sets unchanged. Retain final session/actor
  checks under account admission, including admin cookies and logout. Refresh
  first resolves its owner without locks, then takes account admission, then
  locks the token row and rechecks session/user state.
- Ship `X-Someday-Error-Code` on every newly introduced failure in this stage,
  including bodyless HEAD: `401 account_session_stale`,
  `409 account_incarnation_mismatch`, `409 workspace_incarnation_retired` and
  `503 account_busy` follow the design's route/status/body contract. Existing
  business 409 responses keep their route DTOs. Server errors cannot wait for
  Stage B while Stage A already produces them. Route all existing generic errors,
  including the global handler, through the same mapper. Ship discovery and
  receipt GETs here too: an A-only server advertises protocol 1 with reset
  unavailable (`deployment_not_ready`), never an unexplained discovery 404.
  Include account signup in captured-incarnation issuance; admin cookies also
  bind their session transaction even though they need no native-client header.
- Fence entity, media, recovery and pairing operations before any fast replay
  **or either workspace-registration path**. Split `SyncV2Repository` scope
  setup from `selectWorkspaceScope`'s registry insertion: pin isolation, set
  scope, acquire account admission, check authority/workspace, then take narrower
  locks and insert/replay/write. A check only in the old business block is late. Preserve
  connection-free blob reads and media PUT's existing publication order.
  Move pull's UUID-only, post-repository `touchDevice` write under admission
  with captured account/incarnation and matching enrollment; it must not update
  a newly enrolled device from an old callback.
- Filter content availability and media quota by active incarnation, including
  active invitation counts. Keep invitation `data_incarnation`: the eight-invite
  limit covers active/unexpired rows, not completed/cancelled or stored expired
  rows. Retire invitations logically and clean payloads in bounded maintenance
  batches instead of relying on a fixed-size DELETE during reset. Authentication
  budgets and the three-committed-resets-per-24-hours limit remain account-wide.
  Serialize the invitation count/insert with a narrow invitation-quota lock;
  shared account admission alone does not enforce the eight-active-invite limit.
- Add a Flyway version above frozen V9. Create
  `someday_account_data_incarnations` and receipt tables, seed G0, backfill
  immutable session/workspace/pairing `data_incarnation` and mutable device
  enrollment, then validate ownership FKs and indexes. New account-only tables
  follow auth-table conventions: no RLS, explicit user predicates. Existing
  workspace RLS and exact catalog verification remain intact.
- Install a Flyway-owned user `AFTER INSERT` trigger that creates exactly one
  active G0 row in the same transaction. Cover common account creation,
  `AdminBootstrap` through that shared path, direct SQL fixtures and multirow
  inserts. No user-incarnation pointer, pending incarnation, circular receipt
  FK or reset revision is needed.
- Document the controlled existing admin wildcard path; ordinary account
  requests retain exact user scope. Preserve actor/target authorization and
  explicit target predicates. Test restricted-role isolation, existing RLS
  catalog and controlled administrative aggregates. Align active/retired quota
  and reporting without adding an account-only policy shape.
- Update the architecture gate's literal `loadEpoch` signature check when entry
  points accept authenticated contexts. Retain checks/evidence for explicit
  user/workspace SQL predicates and cross-account isolation; do not leave an
  unused old-signature wrapper to satisfy the gate.
- Add incarnation-aware `MediaBlobKey`: G0 paths stay unchanged; S3 uses
  `media/v1/.incarnations/v1/<userId>/<incarnation>/<workspaceId>/...`, and
  filesystem uses `.incarnations/v1/<userId>/<incarnation>/<workspaceId>/...`.
  Existing `media/v1/*` policy/retention can cover S3; verify explicit subpath
  exceptions and actual nested-path access, without granting runtime list/delete.
- Run the legacy probe plus a probe using real nested-path key construction at
  startup whenever any active nonzero incarnation exists. Stage B1 also requires
  it before enabling the first reset.
  Failure refuses startup independently of reset availability.
  Untouched G0 with reset disabled needs only existing readiness. Certify active
  object protection independently; read/write probes cannot prove delete denial.

Do not expose reset on a partially fenced server. Stage evidence comes from the
upgrade, admission and concurrency cases in section 4.

### Stage B1 — atomic logical reset and storage readiness

Implemented and verified by the reliability and Apple acceptance gates. The
[current server protocol](../../specs/account-data-reset-protocol.md) describes
the resulting reset API. Client reset/rejoin flows remain in C/D, and rollout
remains in E; no deployment is enabled by this implementation status.

- Add reset POST DTOs/routes and `ServerContext` composition for the single commit in
  design section 5.2. Persist only committed receipts. Under `(user, operationId)`
  compare the stored protocol version and validated v1 `expectedIncarnation`;
  protocol/confirmation are validated constants. Do not add `requestDigest` or another serialization/hash
  format. Reuse bounded password verification without coordination workers.
- Add `SOMEDAY_ACCOUNT_RESET_ENABLED` with default/strict parsing and disabled
  deployment templates. The checklist records operator protection/retention
  policy; runtime probes establish only their specified read/write abilities.
- Implement the prior-retired-media gate immediately: a null
  `media_reclaimed_at` blocks a subsequent new reset. Receipt replay and ordinary
  sync remain available. Retain the separate three-per-24-hours limit, which
  still applies after fast reclamation; cleanup does not replenish it.
- Exercise Stage A password-login-backed re-enrollment after actual reset
  commits, preserving global UUID claims, permanent manual revocation and
  immutable old session/workspace authority. Client consent flows ship in C/D.
- Extend the Stage A nested-layout startup requirement to apply when reset is
  enabled, even before any nonzero incarnation exists. Certify active-object
  protection independently; read/write probes cannot prove delete denial.

B1 can ship a first logical reset without B2. Null reclamation markers keep
repeat reset closed until operator capability exists and succeeds. Recommended
indefinite R2 retention can keep it closed permanently; do not weaken that lock.

### Stage B2 — optional operator cleanup and reclamation certification

Implemented and verified by the reliability acceptance gate. The
[maintenance guide](../../guides/account-data-reset-maintenance.md) describes
the separate operator commands, storage certification and restore invalidation.
Acceptance includes passing restricted-role CLI coverage on the supported Linux
container, alongside PostgreSQL and S3 tests. An unsupported filesystem JVM must
fail closed; its rejection tests do not replace Linux cleanup evidence.

- Implement `purge-retired-account-data` as the sole owner of FK-safe bounded SQL
  cleanup and blob deletion, with dry-run, exact targets and separate maintenance
  credentials. Apply design section 9.2's backend barrier, full scans and
  conditional certification without a PUT ledger. Capture retirement time after
  reset acquires its exclusive lock; use an injected clock for settling tests.
- Acquire exclusive account admission for marker changes; storage enumeration
  must not keep a pooled DB connection for its duration. Completion certifies
  media reclamation, not merely the number of deleted SQL rows. Keep invitation
  cleanup bounded despite arbitrarily many terminal rows.
- Cover marker invalidation, late-object assumption violations, interrupted work
  and operator revalidation. Do not describe S3 settling as provider-guaranteed
  finality. Preserve tombstones, receipts and permanent global device claims.
- Update restore tooling to invalidate deletion plans and reclamation
  attestations before resumed reset/maintenance, with coordinated integrity
  checks. Until B2 ships, null markers remain the conservative repeat-reset gate.

B2 is optional for the first logical-reset rollout, but remains tracked work
before this entire plan can be marked completed. It does not introduce a
mandatory purge service or external journal.

### Stage C — shared client transport and durable state

Delivery status: implemented and verified. The shared client
now has typed account control transports, durable intent/gates and capability
memory, incarnation-bound credentials/workspace authority, and guarded
replacement paths. The real v1→v2 upgrade gate passed with nonempty data in all
23 original tables, preserved G0 bindings/media proof, and matching fresh/snapshot
catalogs. Shared-data JVM tests and future-schema refusal passed. A preserved
pre-change schema-1 JVM factory opened a disposable schema-2 copy without
refusing it, confirming that the new refusal cannot protect old binaries; this
is not a substitute for the released Desktop acceptance exercise in Stage E.
Both real HTTP adapters and the concurrency regressions passed. The complete
reliability gate passed with 696 tests, including PostgreSQL, S3, four real
remote journeys, isolated backup/restore and Linux container maintenance.
The Apple gate passed with 183 simulator tests and the iOS app compile.
During validation, sync/account local writes were serialized with the existing
product boundary, and an offline exit was made to invalidate a confirmation
still awaiting discovery. No reset UI or rollout completion is implied.

- `ClientSettings.kt`: protocol DTOs, fixed error reasons, versioned secure
  credentials and issuance `accountIncarnation`; reset DTOs use
  `expectedIncarnation`, `previousIncarnation` and `newIncarnation`. Keep
  secret-bearing objects redacted; never expose passwords through generated `toString` diagnostics.
- `Someday.sq`: incarnation on the workspace binding, local reset intent with
  earlier-uncertainty state, durable mismatch/network gates, and endpoint/user
  protocol-1 capability memory established by valid discovery, issuance headers,
  or strictly validated typed error metadata. Invalid/unknown codes or
  contradictory status/body/header metadata do not establish capability.
  Error-derived evidence requires a known canonical endpoint and immutable user
  ID; an initial email-only login cannot fabricate that account key.
  Capability memory survives binding changes and is independent of
  deferred rollback history. Preserve local generations and writer storage. Legacy bindings/credentials become G0;
  authority/workspace/digest media proof remains unchanged and needs no new
  column or table rebuild. Permanent workspace tombstones prevent cross-
  incarnation reuse. Defer rollback observation/history/acknowledgement tables.
- End the squashed local-baseline stage before adding `1.sqm`. Freeze `1.db`,
  retain migration history and generate `2.db`. Keep the public
  `:shared:data:verifySqlDelightMigration` task, replacing the baseline-only
  dependency with real nonempty upgrades to the current schema (now 1→3 and
  2→3; frozen v2 remains the Stage C boundary). Keep native `verifyMigrations=false`
  while SQLDelight 2.1.0 ObjectDiffer cannot terminate on the FK graph; this is
  not permission to skip upgrade verification. Open a seeded copy of `1.db`
  with the shared factory, compare its normalized `sqlite_master` and structural
  catalog with the fresh current schema, check integrity/FKs, G0 backfill, unchanged preexisting
  data/media proof and successful file-backed reopen. Remove the gate's blanket
  `.sqm`/multiple-snapshot rejection and update the migration guide at that time.
  The committed snapshot has `user_version=0`; the test fixture must first turn
  its disposable copy into an installed v1 database (version 1 plus nonempty
  data). This fixture setup is not platform/runtime version probing.
- `SomedayJdbcDriverFactory.kt`: refuse newer schemas at the shared schema-aware
  lifecycle before writes. No platform/application PRAGMA probing. Exercise
  actual old Desktop binaries separately on disposable migrated copies.
- **Both** Ktor and JDK transports: read and validate `X-Someday-Error-Code` before
  any route-specific success/business DTO decoder, including accepted 409s for
  chunk, manifest, CAS, cleanup, push and media PUT. Recognized control failures
  use bounded generic error decoding; HEAD uses headers alone. Preserve strict
  business DTO JSON and reject missing/contradictory protocol-1 error metadata.
- `SelfHostedSyncHttpException` and `RefreshingSelfHostedSessionExecutor`: only
  ordinary `401 unauthorized` refreshes once. Stale-session and incarnation
  failures gate without refresh; `account_busy` backs off without credential
  clearing. A wrong reset password is never automatically replayed. Legacy
  fallback requires discovery's exact 404 at the same canonical endpoint, strict
  successful `/me` with the same credential and matching user, and no prior
  protocol-1 evidence. Cross-origin redirects, proxy HTML and other errors never
  qualify. Once protocol 1 is known for endpoint/user, it cannot downgrade.
- `SelfHostedWorkspacePairingService`: change create's 409 invite-ID retry and
  claim/complete/cancel error mapping so typed incarnation failures escape to
  the durable gate before business status branches. Completion remains best-effort
  after local commit, but persist any stale-session/incarnation gate before its
  cleanup exception is swallowed; it cannot undo the committed join.
  `SelfHostedWorkspaceRecoveryService`:
  typed GET/PUT failures likewise take precedence; an incarnation 409 must not
  become `ServerConflict` or clear the pending recovery candidate.
- `SelfHostedSetupService` and `loginAndReconnectBound`: compare issuing
  incarnation to durable binding before ordinary renewal registers a device.
  Mismatch makes zero registration calls and preserves binding/content. After
  immediate successful reset, the confirmation password may be reused only in
  memory for the immediately following ordinary login; drop it on cancellation,
  failure or process loss. This never supplies local discard/rejoin consent.
- `ActiveWorkspaceSessionGuard`, Pair/Recover and protocol/media stores:
  distinguish account authentication from permission to resume old content.
  Capture incarnation with attempts and persist it only with their workspace;
  never copy fresh discovery into old authority. Use existing lifecycle/key
  staging and one atomic local replacement, retaining writer and graph.
- Model the explicit unknown-outcome exit: preserve intent/operation ID and the
  network-publication block while allowing the user to keep/edit/export local
  data offline. Reconcile later; never infer no reset from a timeout plus 404,
  silently clear earlier uncertainty, or automatically start another reset.

Keep independent protocol validation and lock-key vector tests, while removing
request-digest vectors with that format. Exercise actual Ktor/JDK adapters and
secure-credential reopening, not only mocked common transports.

### Stage D — UI and platform integration

Delivery status: implemented and locally verified on 2026-09-28.
The existing platform graph now provides the shared reset workflow and product
admission port. Schema 3 persists rejoin consent on the gate before enrollment,
including mismatch-only workflows without a local reset intent. Frozen v1/v2
snapshots remain upgrade inputs; migration grants no discard consent.

- `SettingsUiController`, `SyncUiState` and settings/account UI: confirmation,
  unavailable/unknown-outcome/remote-committed/local-failure/rejoin flows from
  design section 7. Unknown outcome offers an explicit keep-and-edit-offline
  exit while retaining the intent and network block. Preserve earlier unknown
  submissions after a later definitive failure. No server accepted/423 state,
  purge progress or v1 suspected-rollback/history/acknowledgement flow.
- `SettingsUiStrings`, `LocalizedStringProviders` and English/Chinese/Japanese/
  Korean resources: precise logical-reset and retention/backup warnings,
  neutral incarnation-mismatch copy, export before explicit discard, fixed error
  messages, and login/rejoin consent states. Reuse the in-memory confirmation
  password only for immediate ordinary login; if unavailable, request it again.
- Android/iOS/Desktop factories and roots: wire reset through the **existing**
  graph/coordinator, with cheap constructors and off-main bootstrap. No writer
  rotation, application graph holder replacement or identity rebuild stage.
- Controllers, editors, import/export and background entry points: honor the
  durable network/publication gate and discard stale results on workspace/
  incarnation replacement. New account credentials cannot resume old data-plane
  jobs. Explicit offline editing after an unknown outcome stays local until the
  operation and original binding are reconciled.
- Android reminder coverage must exercise `Fire` with On This Day enabled and
  a prior-year note, so the shared graph's `listPriorYearNotesForDate` path runs;
  retain disabled coverage as well. Use disposable emulator installations.

Verify rendered states and interactions on all three shells, including narrow
layout, large text, keyboard-open confirmation and accessibility. Do not run
connected-test install/uninstall against the owner's real app data.

Acceptance evidence for this stage:

- The reliability gate passed 735 tests, including PostgreSQL, S3, real HTTP
  journeys, isolated backup/restore and container checks. After the final UI and
  missing-credential fixes, the affected JVM suites passed again: shared sync
  236, shared UI 154 and Desktop 8. The migration gate, Android compilation,
  architecture and source/diff hygiene checks also passed.
- The final Apple gate passed 201 tests and the iOS simulator compile check.
- The Android production graph passed all three initialization/reminder
  instrumentation tests on a task-owned emulator, including enabled `Fire`
  with a prior-year note. Native render/input evidence uses a 320-by-640 display
  and Android system font scale 1.5, covers six states and four languages, and
  exercises exact confirmation, masked password, IME Next, keyboard-open
  submission and the explicit offline exit.
- Desktop's three production-Compose render tests cover a real 320-by-640 test
  viewport, exact phrase/password admission, Tab and IME Next focus, persistent
  states and Chinese text. The native iOS fixture passed two workflow/state
  cases, including Next/Done, masking, submission, unknown outcome and the
  explicit offline exit, plus one Chinese dialog case at system accessibility
  text size. Platform screenshots and accessibility evidence are retained under
  `build/account-reset-stage-d/` with a consolidated report. Composition-local
  font overrides alone are not evidence of system text sizing in a separate
  dialog scene.
- Render fixtures use synthetic state and perform no live account reset or
  workspace replacement. The shared workflow tests exercise real local
  database/key/authority composition, including Fresh/Pair/Recover replacement,
  rollback, unknown outcomes, missing credentials, stale callbacks and withdrawn
  consent. Released-package compatibility, live multi-client acceptance and
  operator/storage enablement remain Stage E.

### Stage E — coordinated release

Local acceptance record (2026-09-29): isolated PostgreSQL/filesystem and
S3-compatible test services, synthetic accounts, task-owned Android Emulator
and iOS Simulator, and the Desktop production service graph were exercised.
The uncommitted working tree, rather than HEAD alone, is the tested candidate.
Disposable evidence lives under `build/account-reset-stage-e/README.md` and
`report.json`; per-run reports retain artifact hashes and test-harness failures.

The local exercises cover real reset/receipt HTTP, unaffected control data,
stale-session rejection, separate local-discard consent, Fresh/Pair, lost reset
response and same-ID reconciliation, offline edits and mobile process restart,
nonempty V9 upgrade, and the documented backup rollback boundary. Preserved
old Android/iOS candidates were exercised; their local hashes do not establish
store-release provenance. There is no real old Desktop release evidence.
Desktop UI evidence uses Compose/Skia semantics and the production graph with
task-owned file credentials; graph reopen there is within the same JVM.

Acceptance exposed a Settings completion bug after first authority binding,
then an operation-local workspace capture surviving into a later operation.
Both were reproduced and fixed. Seven new regression cases and 80 related
controller/guard tests pass; the final Desktop UI/HTTP exercise also performs
sync, reset, Fresh and sync again on the same Settings controller. iOS native
journeys identify their own framework hashes; final shared Apple tests and
native build evidence are recorded separately.

After both fixes, the full reliability gate passed 748 tests across 12 phases
(192 seconds, no flaky retries), and the Apple gate passed 208 tests. The real
old Android profile upgraded in place from schema 1 to 3 with its public version
IDs/digests and authority unchanged. After separate Fresh consent, the final APK
kept its writer ID and published a newly created native-editor note whose
version ID/digest exactly matched the current-incarnation server object. Some
native driver cases failed on scrolling or transient feedback selectors; later
independent UI/HTTP/database observations are recorded separately, not relabeled
as passing versions of those cases.

Pre-commit review subsequently tightened late account-error identity checks,
bound-login/registration gate persistence, first-binding draft preservation,
cancelled-batch cleanup and export/replacement coordination. Read-only copies
retain search, and two Japanese messages now explicitly identify the local
workspace and the same account. The updated candidate passed 758 reliability
tests across 12 phases and 215 Apple tests; targeted UI and account-workflow
regressions passed 112 and 44 tests respectively. These counts overlap. The
earlier native journey hashes remain historical evidence; subsequent review
and gate results are recorded under `build/account-reset-commit-review/`.

These results do not certify actual provider permissions/retention, signed
device/store packages, missing released-old-client provenance, or coordinated
deployment. The remaining account-reset rollout and enablement work is:

1. Publish the server with reset disabled through the
   [server release process](../../guides/server-release.md).
2. Certify the deployment's legacy and nested-path storage access/protection on
   disposable resources. Choose logical-only retention or certified reclamation.
   B1 plus C/D can enable the first logical reset before B2; null reclamation
   markers continue to block repeated resets. Drill the documented backup
   rollback behavior without claiming anti-rollback protection.
3. Ship all three clients and exercise real old binaries against the server,
   as well as the new Desktop/JDK path. Record local downgrade limits.
4. Obtain operator opt-in after storage, retention and backup review. Keep reset
   disabled until policy attestation and runtime readiness pass. B2 capability
   needs separate settling/reclamation certification; neither its absence nor
   indefinite R2 protection authorizes weaker storage policy. Image publication,
   migrations and deployment remain separate authorized acts.
   Drain all pre-incarnation server processes before enabling reset: future-
   schema startup refusal does not fence an already-running old process.
5. Exercise a synthetic end-to-end account and unaffected control account. On
   failure, disable new resets and roll forward, retaining receipts, fences and
   startup nested-path checks for active nonzero incarnations.

Do not downgrade to a pre-incarnation server image. An intentional database
recovery point follows design section 9.4, not code rollback semantics. Completion
of this full plan also requires B2 evidence or an explicitly reviewed scope
change; first-reset rollout alone does not complete the remaining work.

## 4. Sole acceptance matrix

Use asymmetric synthetic fixtures: account A with two workspaces and different
images; account B with independent nonempty content; a G0 orphan without any
workspace registry row; a pairing claim plus many terminal/expired invitation
rows; a recovery envelope; a manually revoked device; and another device offline
with an unsynced edit. Check the unaffected account for every destructive/isolation
case. Use barriers, not sleeps, for races. B2-specific rows are required for B2
certification, not for the first B1/C/D rollout.

| Boundary or adversarial input | Required evidence |
| --- | --- |
| Nonempty V9 upgrade; registration, AdminBootstrap and direct/multirow SQL user creation | G0 preserves content/authority; trigger atomically creates exactly one active row; ownership FKs/indexes hold. No mutation of frozen migrations. |
| New account-only tables and existing forced workspace RLS under restricted runtime role | Explicit account predicates isolate incarnation/receipts without new RLS; existing exact RLS catalog and cross-workspace/account isolation pass. Controlled admin wildcard operations remain authorized and target-scoped. |
| Authenticated repository context replaces the old `loadEpoch` signature | Updated architecture gate validates real entry points and explicit user/workspace SQL; cross-account attempts fail. No unused compatibility wrapper solely to satisfy a literal grep. |
| Missing confirmation, wrong password, malformed/duplicate/oversized JSON | No receipt/transition/local deletion. Password/log/request objects do not leak secrets. |
| Wrong reset password through each real transport/session executor | One verifier call/failure-budget charge, no refresh or replay. |
| Ordinary 401, `account_session_stale` 401, incarnation 409, unknown/contradictory metadata and bodyless HEAD | Ktor **and JDK** preserve typed reasons; only ordinary unauthorized refreshes once. HEAD uses the header. Missing/contradictory protocol-1 error metadata fails closed. |
| Typed failure on chunk/manifest/CAS/cleanup/push/media PUT routes that normally decode 409 business DTOs | Header is checked before route DTO decoding; incarnation failure reaches ResetRequired rather than a serializer exception. Existing business 409s retain their strict DTO semantics. |
| Pairing create/claim/complete/cancel and recovery GET/PUT return typed incarnation failure | No new invite-ID retry or InvitationAlreadyUsed misclassification; recovery does not report ServerConflict or clear its pending candidate. |
| Stage A with reset disabled introduces admission failures | Typed headers already ship on every route, including HEAD; issuance includes `X-Someday-Account-Incarnation` without changing legacy successful JSON. |
| Issuance delayed across reset; missing/malformed incarnation header | Credentials retain issuing `accountIncarnation`; no discovery-based retagging; known protocol-1 missing header fails. |
| Same reset ID concurrently, after commit, after response loss/restart | One committed incarnation change; stable receipt after fresh login. Validated identical `expectedIncarnation` replays; a different value conflicts. No request-digest format or password comparison in idempotency. |
| Two reset IDs with the same expected incarnation | At most one commit; no second successor from stale expected state. |
| SQL failure before commit; network/driver failure with unknown COMMIT outcome | Rollback is atomic; uncertainty keeps the same operation ID and never claims unchanged data. No server-pending row. |
| First submission has confirmed account-lock timeout rollback before COMMIT | `503 account_busy`; only that attempt's intent may clear, and only while original binding remains valid. Uncertain rollback/COMMIT cannot emit this definitive code. |
| Earlier POST lost; same-ID retry gets account_busy/rejection or status 404; restart | Earlier uncertainty remains durable; timeout plus 404 is never conclusive and no new ID starts automatically. Let the first request commit after the retry fails, then reconcile the one receipt. |
| User exits unknown outcome to keep/edit locally, restarts, later reconciles | Local edit/export works offline; intent and network-publication block persist. A committed receipt enters explicit rejoin/discard flow; late responses cannot silently erase offline edits. |
| Password hash, disable, session revoke or device revoke changes during reset password verification | Exclusive reset recheck rejects stale authority/evidence; control-account state is untouched. If reset admits first, the ordinary shared mutation waits for reset to commit. |
| Ordinary logout/revoke/disable races an already-admitted request or credential issuance | Existing token/device row-lock guarantees hold; otherwise admitted work may finish. Subsequent requests recheck current authority. No implicit commit-level revocation barrier or new inverted session/token lock order. |
| Entity read/push/checkpoint/CAS/replay paused before admission | Reset-first rejects at repository; operation-first commits before reset and belongs to retirement. |
| Entity `selectWorkspaceScope` insertion and media `ensureWorkspace`, including no epoch | Admission precedes both inserts/replays. Neither recreates a retired registry ID or creates old-incarnation metadata after reset. |
| Recovery GET/PUT/replay and each pairing transition overlap reset | Stale session or mismatched incarnation rejected before replay; envelope removal and revision restart stay correctly scoped. |
| Eight active retired invitations plus many terminal/expired rows; zero then eight current invitations | First current invite succeeds; ninth current active invite fails. Retired rows do not count, and reset does not depend on deleting at most eight rows. B2 payload cleanup is bounded; auth/reset budgets remain account-wide. |
| Retired media plus current workspaces within and over quota | Ordinary quota sums all workspaces of active incarnation only; retired bytes remain subject to reclamation gate. No cross-account accounting or reset-based auth budget refill. |
| DB default REPEATABLE READ; account/quota contention | READ COMMITTED is pinned before scope SQL; waiting requests observe committed reset/upload, including the prerequisite two-upload quota regression. |
| Slow media PUT while G0 device logs in/registers/re-enrolls or revocation/admin request runs | Ordinary operations use shared account admission with existing row locks. Registration creates no exclusive account barrier or account_busy burst solely from that PUT. |
| Multiple PUTs hold shared admission and wait on quota; immutable replay performs PUT then GET; reset waits | No claimed 45-second drain guarantee. Exclusive account lock either succeeds or times out with confirmed rollback at its wait budget, without partial reset. |
| Exclusive reset/purge-marker waiter, burst of same-account requests larger than pool, account B traffic | Shared account waits use the 250 ms budget and release connections on rollback; account B keeps progressing. Marker work does not retain a connection during storage enumeration. |
| Logout, /me, /devices, capabilities, control status and every admin route | Each matches the route/lock/incarnation matrix; no unclassified bypass. Stale sessions cannot use control routes; exact receipt replay exempts only the old expected incarnation, not current authentication. |
| Admin actor and target differ; opposite requested order and advisory-key collision | Canonical account-lock ordering avoids deadlock; actor/session and target rechecked; only the intended target changes. |
| Admin actor reset between route authorization and target mutation; admin self-reset | Reset-first rejects stale actor; mutation-first finishes before reset. Self-reset preserves role but requires fresh console login afterward. |
| More stalled media downloads than pool size with reset/other-account traffic | Connections released before blob IO; unrelated operations progress. Captured old response or typed stale-incarnation failure, never republish repair. |
| Media PUT timeout, SQL rollback and late object completion | Bytes remain under retired namespace; no active adoption. B2 waits for settling and full empty scan, not a failed-PUT ledger. |
| Old refresh token, forged current header, legacy client logs in again | No session incarnation upgrade or retired workspace access; protocol gate still blocks old client after reset. |
| Same stable UUID re-enrolled after current-incarnation password login | New session usable only for current incarnation; old session/workspace denied. UUID remains unchanged after lost registration response. |
| Bound renewal returns different incarnation; matching control | Mismatch makes zero registration calls, persists gate and preserves binding/content. Matching renewal works; explicit rejoin registers only after consent. |
| Stale/device-bound re-enrollment; manually revoked or other-account UUID | No unauthorized enrollment; manual revocation and global claims remain permanent. |
| Re-enrollment races reset or retired-device cleanup | Result belongs to captured incarnation; old cleanup never changes/deletes newer enrollment. |
| Device-session refresh races registration revoking its sessions | No deadlock: refresh resolves owner before locking, then completes first or rejects stale state after final row recheck. |
| B1 only: second reset while earlier retired media marker is null, even after SQL cleanup | New reset is blocked; no incarnation advance or extra quota window. Exact receipt and current sync remain available without B2. |
| Three resets within 24 hours with successful fast reclamation between them | Fourth new reset is rate-limited. The three-per-day budget is distinct from retired_media_pending and is not reset by cleanup. |
| B2: retired prefix contains orphan/version/delete marker beyond first page | Full enumeration finds it; deleting it does not certify that scan. Only subsequent complete empty scan under backend barrier can certify; partial/error scans cannot. |
| B2: S3 scan just before versus at 24-hour deadline; long pre-reset lock wait | No early certification. Deadline uses retirement timestamp captured after exclusive lock acquisition; injected clock avoids real waits. |
| B2: synchronous filesystem publication drained; retired namespace empty | No 24-hour delay required. Uncertain publishers must be stopped/drained; current files and other-account roots survive. |
| B2: late object after certification; interrupted/rerun command | Executing command audits marked targets and clears marker first; flags assumption violation without recertifying. Dry-run only reports; operator must disable/revalidate profile. |
| B2: G0 and nested-incarnation cleanup with current upload/account B | Only the proven retired root changes, including G0 orphans. Current image, tombstones, receipts and other account survive. |
| B2: interruption, duplicate run, malicious path/symlink, retention/permission failure | Default dry-run, bounded idempotent deletion, no escaped target or false completion. |
| B2: SQL cleanup interrupted between FK-dependent batches; retention blocks blobs | Safe restart; receipts/workspace/device claims/current content preserved. SQL deletion cannot set the media marker. |
| Legacy probe passes but nested path denied; reset enabled | Startup refuses before first reset; no logical reset followed by unusable current media. Verify real key construction and explicit policy exceptions. |
| Existing active nonzero incarnation, reset disabled, nested path denied | Startup still refuses. Disablement never removes required checks for already-active media. Untouched G0 with reset disabled retains legacy-only readiness. |
| Reset setting absent/false/true versus empty, uppercase or arbitrary value | Default off; exact booleans in local/production. Invalid startup fails; templates never auto-enable. Disabled state preserves fences/receipts/sync. |
| Both probes pass but an explicit nested-path retention/permission exception removes protection | Policy certification fails despite successful runtime reads/writes. Do not expand default media/v1/* permissions or claim probes prove deletion denial. |
| Finite retention expiry and indefinite recommended R2 lock | Runtime cannot delete active media. R2 can permanently block repeat reset; first logical-reset rollout does not require removing retention or completing B2. |
| Nonempty frozen 1.db and 2.db upgrades through shared factory versus fresh schema 3; file-backed reopen | Real verifier runs despite native verifyMigrations=false; normalized schema/catalog, integrity and FKs match. Existing content/media tuple unchanged, new binding G0, intent/gates safe, writer stable. |
| Local media proof reused after account/workspace change or attempted workspace cross-incarnation reuse | Existing authority/workspace/digest tuple is sufficient; binding/session gate plus permanent server tombstone rejects stale authority. No incarnation column or media table rebuild. |
| Valid protocol-1 discovery/issuance/strict typed-error evidence across restart, sign-out and binding switch | Endpoint/user capability memory persists independently of rollback history. Error-derived evidence requires an already known immutable account ID; an email-only initial login cannot invent it. Malformed/unknown/contradictory metadata is not evidence. Once known protocol 1, a later 404/missing header cannot downgrade it. |
| Exact discovery 404 on same canonical endpoint plus strict authenticated /me matching credential/user | Legacy fallback only when no protocol-1 evidence exists. Cross-origin redirect, proxy HTML, malformed body, other status or different user never qualifies. |
| Local intent write fails; process dies before/after POST | No POST before durable intent. Unknown result resumes same operation ID without automatic discard/new reset. |
| Incarnation rejection versus ordinary wrong-password rejection on first submission | Former keeps ResetRequired; latter clears only matching intent if original binding is valid and no earlier submission unresolved. |
| Remote commit then key-store/SQL/credential-write failure | Old local data survives precommit failure but remains network-gated; committed target resumes safely; no second remote reset. |
| Pair/Recover after re-enrollment; cancel/fail before local commit | One replacement on success, no intermediate empty workspace or duplicate discard prompt; failure keeps original local copy. |
| Pair/Recover response/local commit crosses another reset | Install keeps captured incarnation and remains fenced; discovery never promotes an old package. |
| Queued old worker/editor/import callback after workspace replacement | Same graph/coordinator rejects captured stale workspace/incarnation before applying write or state. |
| Account/workspace switch or withdrawn consent during unknown outcome | Old attempt cannot delete newly selected data; intent/receipt remains independently reconcilable. Offline exit never authorizes network sync of old content. |
| Immediate success→login versus cancellation, failure or process loss | Memory-only password can perform one immediate ordinary login; otherwise discarded and requested again. No persisted password, automatic device re-enrollment or implied local discard. |
| Backup before reset restored in isolated deployment | Demonstrate return of old data/authorizations and missing receipt; mismatched client preserves local copy with export/explicit-discard flow. Matching/same-incarnation restores may be undetected; no v1 history detector claimed. |
| Fresh client, unseen incarnation, same UUID at different endpoint/account | No invented UUID ordering or cross-account history. Ordinary mismatch is neutral; no rollback observation table or acknowledgement flow is required. |
| B2: backup restores objects after prior certification | Invalidate attestations/deletion plans before reset/maintenance resumes; no deletion of newly active restored root. |
| New client/old server, old client/G0, old client/reset account | Verified legacy fallback and full compatibility matrix; existing strict JSON stays unchanged. |
| New guard opens future local schema; actual old Desktop binary opens migrated copy | Guard refuses before writes; characterize old binary without claiming retroactive protection. Server downgrade also refused. |
| Rendered confirmation/unknown/offline-edit/committed/local-failed/rejoin/unavailable states | Android/iOS/Desktop UI, keyboard, large text, semantics and four locales; export and explicit discard preserved. No purge-complete/server-pending/suspected-rollback claim. |
| Android reminder enabled/disabled and foreground/background transitions | Prior-year query uses shared graph; durable network gate and no-main-thread-IO apply to both entry paths. |

### Test homes and gates

- Protocol/DTO/lock-vector coverage: `shared/sync/src/commonTest`, `server/src/test`;
  concrete JDK transport tests in JVM source sets and real Ktor transport tests
  use actual headers, business 409s, generic failures and bodyless HEAD.
- Persistence/migration/reopen/fault injection: `shared/data/src/jvmTest`,
  `shared/sync/src/jvmTest` and platform bootstrap tests with schema-aware drivers.
  The preserved Gradle migration gate must execute the real 1→3 and 2→3 suites, not
  merely stop rejecting `.sqm` files or compare empty snapshots.
- PostgreSQL isolation/races/admission/upgrade: `server/src/integrationTest` with
  restricted runtime roles; no in-memory substitute for lock behavior.
- B2 media command/retention/profile tests: disposable filesystem and S3/versioned
  storage, destructive permission checks through maintenance credentials and
  injected-clock settling boundaries. Tests check the conditional rule, not a
  provider guarantee about late publication.
- Real HTTP journeys: `integration-tests/src/test`; two existing devices plus
  a fresh keyless client, reset/rejoin/text/image sync, unaffected second account.
- UI/platform: shared UI tests and disposable native shells, inspecting rendered
  screenshots and exercised interactions; no owner's device data as fixtures.

Required existing gates remain:

```bash
./gradlew :shared:data:verifySqlDelightMigration
scripts/verify-system-v3-architecture
scripts/sync-v3-reliability-gate
scripts/sync-v3-apple-gate
```

Keep their public names while replacing obsolete baseline/signature assumptions
with the revised ownership and migration checks. Extend `scripts/server-recovery-gate`
for the documented restore boundary and B2 cleanup-plan invalidation; extend
container smoke/release checks for nested-path readiness and disabled rollout.
Run Android, Desktop and iOS simulator compile checks. Preserve unchanged
pairing/recovery/media cryptographic vectors.

Keep architecture checks rejecting LIST/DELETE in ordinary runtime media
classes; scope an exception only to the separate maintenance command. Preserve
one authoritative local DAG and its existing local generation terminology.

## 5. Implementation review and completion

The revised design was accepted for implementation on 2026-09-28 after
independent review and preflight, preserving R1–R10. Assess each implemented
change against the invariant IDs without duplicating their definitions here.
A blocking finding identifies an input/interleaving, violated invariant and
smallest necessary correction.

Focus on missed admission/replay/registry paths; mutable device versus immutable
session authority; actor/target and refresh lock ordering; shared-lock behavior
on G0 and account-lock wait budgets under pool pressure; both transports and
business-409 precedence; real upgrade evidence; quota/reclamation gates despite
unindexed or late objects; local consent, offline uncertainty exits and stale
callbacks; and accurate backup limitations. Test competing unsafe behavior,
not merely a successful empty-account reset.

V1 excludes writer rotation, graph replacement, external retirement journals,
durable server-pending phases, user-facing purge progress and local rollback-
history/acknowledgement machinery. Ordinary mismatch, export and explicit local
discard remain required. Additional guarantees need a separate decision and
evidence. Completing the whole plan requires fresh matrix/release evidence and
B2 completion or an explicit scope revision; first logical-reset rollout and
design acceptance satisfy neither that completion nor deployment authorization.
