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
envelope authentication. It is deterministic, has no database or network, and
runs on every supported Kotlin target.

All field-level merge combinations belong here. Higher layers prove that the
real composition reaches this model; they do not repeat its full matrix.

### Local persistence

Location: `shared/sync/src/jvmTest` and `shared/data/src/jvmTest`.

This layer covers SQLite transaction boundaries, the durable outbox, cursor
advancement, checkpoint resumption, dead-letter evidence, app-private media
promotion, and restart recovery. Tests use file-backed SQLite when reopen is
part of the invariant and inject a failure at an explicit durable boundary.

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
boundary. Integration tests use real PostgreSQL; filesystem tests use a real
temporary directory, and S3 tests use a pinned compatible service. Repository
publication tests may replace the blob boundary with
a controllable implementation to force a precise write, corruption, or orphan
condition. Both real backends remain covered separately.

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
4. pairing, atomic workspace replacement, bootstrap, and visible notes.

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

Checkpoint tests cover interruption after a chunk, after the manifest, after
the pointer, and before local activation. Multi-chunk preparation retains one
durable identity across reopen and retry.

Server tests cover atomic multi-object push rejection, concurrent genesis CAS,
concurrent cursor allocation, pull pagination and cursor rollback, and exact
replay. Required target media tests cover blob-write failure, durable orphan
reuse after a database failure, missing-object reconstruction by exact PUT,
immutable mismatch rejection, same-key concurrency, and an account-wide quota
race across workspaces.

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

`scripts/managed-storage-profile-gate planetscale|r2` applies the focused
recovery journey to dedicated managed resources. A named profile is verified
only when a current `result.json` records a complete passing live run.
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
