# System V3 Test Strategy

Status: implemented.

The synchronization suite proves protocol invariants and recovery behavior at
the lowest useful layer. End-to-end tests cover a small number of complete user
journeys.

## 1. Four test layers

### Protocol model

Location: `shared/sync/src/commonTest`.

This layer covers canonical encoding, cryptographic vectors, schema and size
bounds, DAG validation, deterministic merge, conflict semantics, and media
envelope authentication. This layer is deterministic, has no database or
network, and runs on every supported Kotlin target where the primitive is
available.

All field-level merge combinations belong here. Higher layers prove that the
real composition reaches this model; they do not repeat its full matrix.

### Local persistence

Location: `shared/sync/src/jvmTest` and `shared/data/src/jvmTest`.

This layer covers SQLite transaction boundaries, the durable outbox, cursor
advancement, checkpoint resumption, dead-letter evidence, app-private media
promotion, restart recovery, secure-key staging, and atomic Pair/Recover
workspace replacement. Wrong codes, tampered envelopes, and injected failures
before commit must preserve every prior local row and key alias. Tests use
file-backed SQLite when reopen is part of the invariant and inject a failure at
an explicit durable boundary.

Remote-apply batching tests use the production server shape of one encrypted
object per cursor unit. Two hundred units must share one outer SQLite
transaction; 201 units cross into a second transaction without changing their
individual cursor identities. The 934-unit regression crosses two 500-unit
pull pages and records transaction sizes `200, 200, 100, 200, 200, 34`. A
structured rejection or injected SQLite fault at unit 101 proves that the
current batch has no partial versions, projections, replay identities, or
cursor progress before exact retry. Cross-stream missing-parent tests keep the
whole pull page as the scheduling window while the local transaction remains
bounded.

Workspace-recovery JVM tests cover 128-bit code generation and normalization,
portable metadata without device-local aliases, KDF/AEAD authentication,
envelope identity binding, secret redaction, setup confirmation, and atomic
replacement behavior.

Account reset client coverage uses the same local persistence and lifecycle
boundaries. `SqlDelightAccountStateRepositoryTest` proves that intent and gates
commit together, uncertainty survives reopen and later rejected attempts,
workspace replacement cannot bypass an unresolved intent, and offline editing
invalidates discard consent. Confirmation freezes the original copy before
discovery; failed discovery or process loss cannot restore earlier consent.
Completion shares the workspace-replacement transaction, so injected local
failure restores both intent and gate.

`SelfHostedAccountResetServiceTest` covers persistence before POST, lost responses,
receipt-404 ambiguity, fresh consent after offline editing, and concurrent
submission without holding the workspace lifecycle lock through HTTP. A barrier
also proves that an offline exit while confirmation discovery is pending
invalidates that confirmation and preserves subsequent local edits.
`AccountDiscoveryPersistenceTest` and `AccountSessionExecutorTest` cover strict
same-credential legacy proof, monotonic protocol capability, issuance during
legacy verification, one ordinary unauthorized refresh, no refresh for stale
incarnation or busy responses, and rejection of late credential updates.
Protocol evidence can come from valid discovery, issuance, or fully validated
typed error metadata; malformed or contradictory metadata is not evidence.
Error-derived evidence belongs to an already known canonical endpoint/account
ID; an initial login with only an email address must not fabricate the user ID.

`SelfHostedAccountTransportTest` exercises both actual Ktor and JDK adapters
against a local HTTP fixture. It checks typed errors before route-specific 409
decoding, bodyless HEAD classification, bounded strict control/error bodies,
receipt identity, issuance headers, and renewal mismatch before registration.
The secure-credential storage tests cover old-envelope G0 migration and the new
incarnation/protocol fields. These tests complement Pair/Recover replacement
coverage; they do not substitute for platform UI or release acceptance.

File-backed lifecycle tests serialize sync bookkeeping and account callbacks
against a product read/write transaction. They cover same-thread product-lock
reentry, cross-thread exclusion, and rollback of intent/gate deletion inside the
existing replacement transaction. The original activation/replacement exclusion
tests remain in place. Network waits are coordinated with latches rather than
adding SQLite busy retries or increasing timeouts.

`SelfHostedAccountResetManagerTest` exercises the product workflow through real
local repositories, including secure-key replacement callbacks and replacement
rollback. A barrier allows real pairing to replace the local copy before an
older control request returns an incarnation error; the new copy must remain
unfrozen. Bound setup tests also cover reset between password login and device
registration, and require the old-copy gate to be saved before releasing the
workspace lifecycle lock.

`AccountResetUiControllerTest` covers the UI dispatcher boundary, request
identity, cancellation, and local versus remote completion feedback. Export
and local replacement are serialized, and a late export result cannot report
backup success for a replaced copy. `WorkspaceProductUiGuardTest` checks that
only the next revision of the same workspace's initial binding preserves an
unsaved editor, cancelled batches release their own busy state, and read-only
copies remain searchable without permitting writes.
`SettingsFirstAuthorityBindingTest` verifies that the first successful authority
binding preserves pulled settings and completes UI feedback, while replacement
or concurrent identity changes still reject stale writes. It also exercises
sync, Fresh replacement and another sync on one controller, ensuring an
operation's captured workspace cannot leak into the next operation.
`AccountResetDesktopRenderTest` renders the production reset content at an
explicit 320-by-640 desktop test viewport, checks password and exact-phrase
requirements, Tab/IME Next focus, persistent outcomes, and large Chinese text. Set
`SOMEDAY_REVIEW_ARTIFACTS` to retain its Skia-rendered images.

Platform graph checks keep the same production composition: the isolated
Desktop graph test reopens the same workspace and stable writer, while Android
instrumentation exercises enabled reminder `Fire` with a prior-year note and
subsequent product writes through the shared application graph. Render-only
mobile fixtures require the explicit `someday.resetRenderFixtures=true` build
property and use disposable emulators/simulators. They exercise the production
forms with synthetic account state; they do not prove live reset requests or
released-package compatibility. Normal builds exclude these fixture sources.

The public `:shared:data:verifySqlDelightMigration` gate runs real nonempty
schema-1-to-3 and schema-2-to-3 upgrades through the shared JVM factory. All 23
original tables and all 26 schema-2 tables contain related data; every original
column value survives, and the upgraded catalog matches both a fresh database
and the schema-3 snapshot. It also proves G0 authority backfill, unchanged media
proof, preservation of pending reset intent and gates without granting discard
consent, repeat opens, and future-schema refusal. See
[database migrations](database-migrations.md) for the frozen snapshot and
replacement-verifier rules.

The standard shape is:

```text
arrange durable state -> inject one failure -> close/reopen -> retry -> assert invariant
```

### Server

Location: `server/src/test`, `server/src/integrationTest`, and
`server/src/s3IntegrationTest`.

This layer covers HTTP validation, authentication, device revocation,
account/workspace scope, PostgreSQL transactions and RLS, immutable object
semantics, cursor allocation, account quota, and the database/blob publication
boundary. It also covers the device-bound recovery-envelope GET/PUT routes,
`no-store`, bounds and digest checks, one current pointer per account, exact
replay, revision compare-and-set, and concurrent replacement. Integration tests
use real PostgreSQL; filesystem tests use a real temporary directory, and S3
tests use a pinned compatible service. Repository publication tests may replace
the blob boundary with
a controllable implementation to force a precise write, corruption, or orphan
condition. Both real backends remain covered separately.

Account reset tests exercise the implemented server boundary from
[the incarnation protocol](../specs/account-data-reset-protocol.md): real
PostgreSQL admission waits, retirement timestamps after publishers drain,
atomic rollback, uncertain commit followed by receipt lookup, password-snapshot
changes, current-session replay, and strict request validation. Readiness probes
must release database connections before storage IO.

Retired-data maintenance tests cover bounded SQL deletion, preserved workspace
tombstones and device claims, complete version/delete-marker pagination, partial
listing failures, retention denial, and persistent late-object violations. The
container packaging gate runs the actual Linux filesystem maintenance launcher
with a separate restricted database role, while unsupported host filesystem
providers must prove rejection without deletion. The S3 suite uses a separate
maintenance identity and a real multi-page versioned namespace. Restore tests
invalidate prior certification through the packaged operator command before
reopening ingress. These checks do not certify a production provider's retention
or remote-write settlement assumptions. Client reset/rejoin acceptance remains
in the [implementation plan](../plans/account-data-reset/implementation.md).

### Real self-hosted journeys

Location: `integration-tests/src/test` under the `realRemoteTest` task.

This layer starts from public production composition and crosses a real HTTP
socket, installed server, PostgreSQL, the configured blob service, and
independent client databases. The complete journey runs with PostgreSQL and a
pinned S3-compatible service; standalone filesystem deployment receives a
packaging/storage smoke test rather than a duplicate product journey. The layer
contains only a small set of product journeys:

1. bootstrap and non-conflicting two-device convergence;
2. durable same-field conflict on both devices;
3. image import, media-first publication, entity sync, and lazy materialization;
4. pairing, atomic workspace replacement, bootstrap, and visible notes;
5. recovery-code setup, fresh-client recovery, bootstrap, and visible notes.

Account reset has a separate `:integration-tests:accountResetJourneyTest` task.
Run it only against a disposable server with registration and
`SOMEDAY_ACCOUNT_RESET_ENABLED=true`, supplying `SOMEDAY_E2E_ENDPOINT` and the
matching `SOMEDAY_DB_URL`, `SOMEDAY_DB_USER`, and `SOMEDAY_DB_PASSWORD`. The normal
`realRemoteTest` task excludes it so the default reset-disabled gate stays
meaningful. The reset task always executes rather than reusing an up-to-date
test result.

Its JDK/Ktor journeys verify nonempty text and image data, an unaffected control
account, stale-session and legacy-wire refusal, separate local-discard consent,
fresh and paired replacement, stable writer identity, and lost-response
reconciliation with the same operation ID. PostgreSQL assertions fingerprint
all six entity tables, and local installations reopen file-backed SQLite.
These tests emulate legacy wire requests; they do not replace acceptance with
actual old binaries, OS process restarts, platform secure storage, or provider
policy certification.

An E2E journey asserts externally meaningful state. It may observe a public
cross-plane boundary to prove that media is already durable before entity
publication, but it does not inspect private implementation call order or
reproduce every model-level merge case.

## 2. Fixture boundary

Shared test support provides mechanical setup and cleanup:

- a device owns one temporary database and one stable UUIDv4, can close and
  reopen that database, and uses a deterministic clock where time affects the
  asserted invariant;
- a workspace fixture owns a key, canonical workspace ID and checkpoint source;
- a faulting remote exposes named one-shot transport faults;
- a server fixture owns accounts, devices, workspace scope and cleanup;
- convergence assertions compare the DAG heads, projections, conflicts,
  cursors and outbox state that form the product invariant.

Fixtures do not choose business actions, hide retries, catch unexpected
failures, or form a scenario DSL. IDs, clocks, payloads and fault points are
explicit in each test. Tests do not depend on execution order or fixed ports.

## 3. Failure matrix

The local persistence layer must cover each ambiguous entity delivery result,
followed by a real database reopen:

| Remote observation | Required result |
| --- | --- |
| failure before commit | outbox remains; retry stores once |
| failure after commit | outbox remains; exact replay acknowledges once |
| acknowledgement lost | outbox remains; exact replay acknowledges once |
| acknowledgement corrupted | no outbox row is acknowledged |
| pull failure | local cursor and projection do not advance |
| structured rejection inside a pull batch | current batch rolls back; valid prefix is retried and committed before exact blocker evidence |
| SQLite failure inside a pull batch | every unit in the current batch rolls back; exact retry commits once |

Checkpoint tests cover interruption after a chunk, after the manifest, after
the pointer, and before local activation. Multi-chunk preparation retains one
durable identity across reopen and retry.

Server tests cover atomic multi-object push rejection, concurrent genesis CAS,
concurrent cursor allocation, pull pagination and cursor rollback, and exact
replay. Required target media tests cover blob-write failure, durable orphan
reuse after a database failure, missing-object reconstruction by exact PUT,
immutable mismatch rejection, same-key concurrency, and an account-wide quota
race across workspaces.

Real-PostgreSQL tests also hold the recovery account advisory lock and prove
that both first-epoch CAS and recovery-envelope PUT wait on it. They publish an
account-current recovery pointer, reject a competing genesis with
`workspace_recovery_required` without creating an epoch, and prove that an
existing workspace's exact epoch replay remains available. This is the server
authority boundary behind the client's stale-`404` recovery check; a client-only
preflight is not accepted as race protection. The same test starts repository
connections at `REPEATABLE READ` and proves each write path pins
`READ COMMITTED`: a waiting CAS sees the holder's newly committed recovery
pointer, and a waiting recovery PUT sees the holder's newly committed epoch.
Client tests then prove the rejected PREPARING epoch and its provisional local
authority are abandoned before the real recovery service refreshes to
Recovery Available.

The shared backend suite proves immutable
PUT/HEAD/GET, exact replay, canonical length/SHA-256 validation, same-key
concurrency, and the maximum supported object. S3-specific adapter tests prove
conditional create, read-after-write, bounded error/timeout mapping, and no
filesystem fallback. They also cover an object whose metadata claims the
expected digest while its actual payload differs. One pinned S3-compatible
implementation is the release gate; Someday does not duplicate the suite for
individual storage vendors.

## 4. Evidence rules

Two host-native gates provide release evidence.
`scripts/sync-v3-reliability-gate` runs on Ubuntu and covers JVM/Android,
PostgreSQL, installed-server, and real self-hosted journey evidence.
`scripts/sync-v3-apple-gate` runs on an Apple Silicon macOS host and covers shared
behavior plus app-shell execution evidence on the iOS simulator. The real HTTP
transport journey runs in the Ubuntu gate, which provisions pinned PostgreSQL
17 and HTTPS MinIO. Its generated test CA is injected only into gate processes.

`scripts/build-minio-test-image` builds the MinIO server and client fixtures
from checksum-verified upstream source archives with pinned Go and runtime
images. It preserves the previously tested upstream versions without depending
on the unavailable prebuilt MinIO images. Docker caches the builds locally; the
gate report records the recipe-specific tags and actual image IDs. These are
test fixtures, not images shipped with Someday Server.

Together the gates:

- create a dedicated application role with `NOSUPERUSER` and `NOBYPASSRLS`,
  and use it for migrations, server integration tests, the production-mode
  installed server, and real journeys;
- run protocol tests on supported targets and all JVM persistence tests;
- run every PostgreSQL integration test without assumptions or skips;
- start the installed server and run every real self-hosted journey;
- accept only JUnit XML created during the current invocation;
- fail on a failure, error, skipped test, stale result, or missing layer.

The Ubuntu gate also:

- provision a pinned S3-compatible service;
- run the same backend contract against that service and a real filesystem
  directory;
- prove missing-object HEAD/GET is distinguishable from permission denial and
  that the application never invokes listing or deletion;
- prove orphan reuse, no divergent-object deletion, missing-object exact
  replay, and same-key concurrency at the PostgreSQL/blob boundary;
- run the complete installed-server product journey with PostgreSQL and S3;
- prove the operator integrity validator accepts an object-store superset and
  rejects a recovery set with a missing or byte-divergent referenced object;
- capture non-empty PostgreSQL and media as one recovery unit, restore both
  into isolation, and compare ownership, Flyway history, rows, and media
  digests, with missing media required to return status `2`; and
- keep a content-empty paired client alive across that restore, read one note
  and image through a write-blocked ingress, and prove both write planes are
  rejected.

Recovery-code release evidence must combine JVM cryptography, repository, service,
and atomic-replacement tests with real-PostgreSQL recovery-envelope API tests,
an installed-server fresh-client journey, and explicit recovery-envelope
survival through operator restore. Managed-provider certification includes the
same recovery boundary when its scoped changes require that gate.

`scripts/managed-storage-profile-gate planetscale|r2` applies the focused
paired-client recovery journey to dedicated managed resources. A named profile
is verified only when a current `result.json` records a complete passing live run.
The release controller consumes that evidence as described in
[Server release](server-release.md).

`scripts/server-container-smoke` owns the separate packaging boundary: it
builds the production image, starts the standalone Compose topology with a
read-only root and non-root identity, exercises both operator subcommands, and
validates the external Compose configuration. It does not duplicate the sync
journey.

The PostgreSQL administrator identity is limited to container provisioning and
the restricted-role RLS fixture's role management, seed, and cleanup work. It
is never the installed server runtime identity. The gate queries PostgreSQL to
prove the application login is neither superuser nor able to bypass RLS before
accepting any server evidence.

The Ubuntu report excludes iOS evidence. Simulator behavior runs on a capable
Apple host, and compilation is reported separately from transport tests.

Static architecture checks protect suite boundaries without hard-coding test
method names or replacing behavioral evidence.

The account-reset schema change also has a limited compatibility observation:
the preserved pre-change, already-compiled schema-1 JVM factory opened a
disposable nonempty schema-2 database without refusal and left its version at 2.
This demonstrates the old SQLDelight 2.1.0 factory limitation; it does not prove
that all old-client operations are safe on schema 2. The tested artifact was a
local compiled factory, not a released Desktop package or its UI. Formal old
released-binary and platform reset/rejoin acceptance remain in Stage E of the
[implementation plan](../plans/account-data-reset/implementation.md); shared
client Stage C validation is tracked there separately.

Stage D workflow tests compose the real local database, keys, authority guard,
reset manager and Pair/Recover services. Synthetic transports cover response
loss, control-only reauthentication, an in-flight offline exit, missing-gate
recovery, fresh discard consent, and local replacement rollback. Shared UI tests
check background dispatch, duplicate submissions, stale completions and fixed
error messages. The Android enabled reminder test executes `Fire` against a
prior-year note through the platform graph, in addition to disabled coverage.

For rendered checks, the explicit `-Psomeday.resetRenderFixtures=true` build
property adds isolated Compose fixtures to shared UI and disposable mobile
shells. It is absent from normal builds. Fixtures render the real reset
components with synthetic state and perform no network or workspace mutations;
they complement, rather than replace, the real workflow tests. Cover unavailable,
confirmation, unknown/offline, committed, mismatch and local-failure states,
four locales, narrow layouts, large text, keyboard focus and accessibility.
Desktop also has `:app:desktop:runIsolatedUiShell` with a temporary profile and
no owner Keychain access; `-Psomeday.isolatedResetScenario=ready` selects synthetic
reset UI within the otherwise real shell.

Use newly created Android/iOS simulators and explicit device identifiers. Never
run installation, instrumentation, application-data clearing, or uninstall steps
against an owner device. Render screenshots and transient test profiles are
local build evidence, not maintained documentation or release artifacts.

## 5. Evolution rules

- Add a case to the lowest layer that can prove the invariant.
- Prefer a table of named fault cases over copied setup.
- Split a file by responsibility when its fixture obscures the behavior under
  test.
- Do not add sleeps for coordination; use latches, barriers or observable
  durable state.
- Every regression test must fail against the broken behavior it describes.
- Randomized convergence and load tests use a printed reproducible seed and
  remain supplemental to deterministic release evidence.
- New media formats, key rotation, or multi-workspace UI add focused journeys
  when those features are implemented.
- Recovery-code replacement is wrapped-key rotation, not master-key rotation;
  changes still add focused prepare/confirm/CAS and historical-envelope cases.
