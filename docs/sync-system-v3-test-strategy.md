# System V3 Test Strategy

Status: first-release verification contract.

The synchronization suite proves protocol invariants and recovery behavior. It
does not optimize for a line-coverage percentage, duplicate the same cases at
every layer, or build a second workflow engine inside test code.

## 1. Four test layers

### Protocol model

Location: `shared/sync/src/commonTest`.

This layer owns canonical encoding, cryptographic vectors, schema and size
bounds, DAG validation, deterministic merge, conflict semantics, and media
envelope authentication. It is deterministic, has no database or network, and
runs on every supported Kotlin target.

All field-level merge combinations belong here. Higher layers prove that the
real composition reaches this model; they do not repeat its full matrix.

### Local persistence contract

Location: `shared/sync/src/jvmTest` and `shared/data/src/jvmTest`.

This layer owns SQLite transaction boundaries, the durable outbox, cursor
advancement, checkpoint resumption, dead-letter evidence, app-private media
promotion, and restart recovery. Tests use file-backed SQLite when reopen is
part of the invariant and inject a failure at an explicit durable boundary.

The standard shape is:

```text
arrange durable state -> inject one failure -> close/reopen -> retry -> assert invariant
```

### Server contract

Location: `server/src/test` and `server/src/integrationTest`.

This layer owns HTTP validation, authentication, device revocation,
account/workspace scope, PostgreSQL transactions and RLS, immutable object
semantics, cursor allocation, account quota, and database/blob reconciliation.
Integration tests always use real PostgreSQL. HTTP, RLS, and journey tests use
the real filesystem blob store; repository publication tests may replace only
that blob boundary with a controllable implementation to force a precise
write, corruption, or orphan condition. That narrow fault seam does not
substitute for the real filesystem path.

### Real self-hosted journeys

Location: `integration-tests/src/test` under the `realRemoteTest` task.

This layer starts from public production composition and crosses a real HTTP
socket, installed server, PostgreSQL, blob directory, and independent client
databases. It contains only a small set of product journeys:

1. bootstrap and non-conflicting two-device convergence;
2. durable same-field conflict on both devices;
3. image import, media-first publication, entity sync, and lazy materialization;
4. pairing, atomic workspace adoption, bootstrap, and visible notes.

An E2E journey asserts externally meaningful state. It may observe a public
cross-plane boundary to prove that media is already durable before entity
publication, but it does not inspect private implementation call order or
reproduce every model-level merge case.

## 2. Fixture boundary

Shared test support may own only mechanical setup and cleanup:

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

## 3. First-release failure matrix

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
replay. Media tests cover blob-write failure, orphan blob cleanup, missing or
corrupt blob reconstruction by exact PUT, same-key concurrency, and an
account-wide quota race across workspaces.

## 4. Evidence rules

The release contract has two host-native gates. `scripts/sync-v3-reliability-gate`
runs on Ubuntu and owns JVM/Android, PostgreSQL, installed-server, and real
self-hosted journey evidence. `scripts/sync-v3-apple-gate` runs on an Apple
Silicon macOS host and owns shared behavior plus app-shell execution evidence
on the iOS simulator. It does not claim a platform-native HTTP transport
journey, which is outside the first-release P0 set. Together the gates must:

- provision an isolated pinned PostgreSQL image and blob directory;
- create a dedicated application role with `NOSUPERUSER` and `NOBYPASSRLS`,
  and use it for migrations, server integration tests, the production-mode
  installed server, and real journeys;
- run protocol tests on supported targets and all JVM persistence tests;
- run every PostgreSQL integration test without assumptions or skips;
- start the installed server and run every real self-hosted journey;
- accept only JUnit XML created during the current invocation;
- fail on a failure, error, skipped test, stale result, or missing layer.

The PostgreSQL administrator identity is limited to container provisioning and
the restricted-role RLS fixture's role management, seed, and cleanup work. It
is never the installed server runtime identity. The gate queries PostgreSQL to
prove the application login is neither superuser nor able to bypass RLS before
accepting any server evidence.

The Ubuntu gate never claims iOS evidence. Simulator behavior runs only on a
host that can execute it; compilation is recorded separately and is never
reported as a passing transport test.

Static architecture checks protect suite boundaries but never hard-code test
method names or replace behavioral evidence.

## 5. Evolution rules

- Add a case to the lowest layer that can prove the invariant.
- Prefer a table of named fault cases over copied setup.
- Split a file by contract when its fixture obscures the behavior under test.
- Do not add sleeps for coordination; use latches, barriers or observable
  durable state.
- Every regression test must fail against the broken behavior it describes.
- Randomized convergence and load tests use a printed reproducible seed and
  remain supplemental to deterministic release evidence.
- New media contracts, key rotation or multi-workspace UI add their own
  journeys; they do not expand the first-release tests speculatively.
