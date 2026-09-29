# Account Data Reset — design

Status: **accepted** (updated 2026-09-29) — revised after independent review and
implementation preflight. The transaction-isolation prerequisite and Stage A
are implemented and verified. B1 logical reset and B2 operator maintenance are
implemented and passed automated acceptance. Stage C shared client transport,
durable state and migration are implemented and passed automated acceptance;
Stage D UI/platform integration is implemented and locally verified;
Stage E local verification is recorded in the implementation plan; actual
provider certification, released-client evidence and coordinated rollout remain
pending.
The overall plan is not completed. Reset defaults off; this plan
does not authorize deployment or real-account operations.

Scope: account-wide logical data reset across the self-hosted server and all
Someday clients. This plan follows the [documentation rules](../../README.md).
It owns the accepted design; [the implementation plan](implementation.md) owns
delivery stages, specification amendments and the acceptance matrix. The
[current account protocol](../../specs/account-data-reset-protocol.md) owns
implemented server and shared-client behavior. This document
does not authorize operations on real accounts.

## 1. Summary and v1 decisions

**Reset account data / 重置账号数据** preserves the account and its password,
retires every existing remote workspace, and permits a fresh start with new
workspace keys. A password is sufficient; an old recovery code, workspace key
or second online device is not required.

One PostgreSQL transaction changes the account's active **data incarnation** and
records an idempotent receipt. All content operations validate incarnation under
the same account lock. Devices keep their stable installation/writer UUID;
password login and explicit re-enrollment give them new-incarnation sessions,
without upgrading old sessions or making old workspaces usable again.

Local replacement remains a separate, user-confirmed transaction. Physical
deletion follows operator retention policy. V1 neither displays deletion
progress nor guarantees that reset survives restoration of an older backup.

The password-only, all-workspace reset and backup/retention limits retain the
previously accepted product baseline. This revision narrows locking, storage
changes and client state; its choices are concrete proposals, not alternatives
for implementers to select independently.

| v1 decision | Consequence |
| --- | --- |
| Password-checked, all-workspace reset | A password-holder can retire data without being able to decrypt it. No selective reset or undo. |
| Stable writer UUID and explicit re-enrollment | One device UUID may appear across incarnations. Session/workspace incarnation, not UUID alone, identifies historical authority. Manual revocation stays permanent. |
| Single SQL commit; no external retirement journal | Normal crashes/retries are covered. Historical restore can revive data **and old authorizations**, including after a completed reset. |
| Bounded account-lock waits | Shared admission waits at most 250 ms behind reset or a purge marker change; those exclusive operations wait at most 45 s. Timeout is retryable `503 account_busy`, not a promise that uploads drain within 45 s. Registration, revocation and admin mutations use shared admission. |
| Logical completion, no user-facing physical-purge states | UI must say that offline copies, exports and retained backups are not erased. Physical removal is an operator responsibility. |
| At most one not-yet-reclaimed retired media incarnation | A subsequent reset may need operator cleanup first. Indefinite retention can prevent further resets indefinitely. |
| Ordinary login again after commit | The initiating session is stale too. The confirmation password may be reused only in memory for the immediately following ordinary login; never persist it or invent a post-reset credential. |
| Old clients supported only on untouched G0 accounts | A reset account requires an incarnation-aware client, even after successful password login. |
| S3 reclamation uses an explicit 24-hour settling assumption | This is an operator-certified storage assumption, not an S3 cancellation or completion guarantee. Unsupported profiles cannot certify reclamation. |
| Local mismatch requires explicit reconciliation | Keep export and separate discard consent. Historical rollback detection is deferred; a restore matching the local binding can go undetected. |

### 1.1 Scope and exclusions

Retire notes, notebooks, synchronized preferences, DAG history/checkpoints,
media access, pairing invitations, recovery envelopes and old synchronization
authority for **all** remote workspaces of the authenticated account. Preserve
account ID, email, password hash, creation time, administrator role and disabled
status. Reset cannot enable a disabled account. Other accounts are unaffected.

Keep minimal incarnation/workspace tombstones, device ownership and receipts.
These records must not retain note content, recovery ciphertext or credentials
merely for audit. Retain installation-local appearance, language and notification
preferences during an authorized local replacement.

This is not logout, password recovery, account deletion, recovery-code rotation,
whole-server reset, in-place key rotation or automatic merging. General media
garbage collection, automatic provider migration, backup anti-rollback and a
malicious-operator-proof erasure guarantee are outside v1. An owner may
deliberately import an old plaintext export into a new workspace afterward.

## 2. Safety invariants and their boundary

| ID | Required invariant |
| --- | --- |
| R1 | Authentication selects the account. No request field selects another user's data. |
| R2 | No content request admitted after reset commit can access a retired incarnation. |
| R3 | An already-admitted mutation commits before reset and is included in retirement, or fails. It cannot commit old-incarnation metadata after reset. |
| R4 | Old tokens, sessions, workspaces, pairing invitations and recovery envelopes never regain current authority through replay or reauthentication. Reusing a stable device UUID changes only its enrollment, not those historical authorities. |
| R5 | Exact operation retries and process restarts produce one committed reset and a stable receipt, not a second reset or a false claim of no change. |
| R6 | A rolled-back reset transaction leaves remote authority unchanged. SQL commit is the only irreversible completion point; the server has no durable accepted-but-uncommitted state. |
| R7 | Local replacement has its own consent and atomic commit. Its failure never rolls back the remote reset or silently discards the previous local workspace. |
| R8 | Cleanup targets only proven retired data, never active data, another account, or the tombstones/receipts required to fence it. |
| R9 | V1 makes **no backup anti-rollback guarantee**. Historical restoration is a documented recovery-point loss that can undo reset, receipts and revocations; see section 9.4. |
| R10 | Passwords, tokens, recovery codes, keys, content and raw provider exceptions never enter receipts or reset diagnostics. |

R2/R3 concern admission and commit, not delivery time. A bounded response admitted
before reset may arrive afterward. Media reads may finish outside the database
connection after capturing their authorized immutable object.

R2–R8 apply within the surviving authoritative database history. R5 covers
ordinary process, network and transaction failures, not a restore that removes
the receipt. The server cannot prove from a restored database alone that no
later reset occurred. Do not describe retained encryption as preventing the
restoration of old authorization: old devices may still hold the matching keys.

## 3. Authority and durable state

### 3.1 Account incarnation is not a DAG epoch

`accountIncarnation` is a lowercase canonical UUID. Untouched accounts, including
new accounts, start at reserved `G0`:
`00000000-0000-0000-0000-000000000000`. Each reset allocates a random UUIDv4 in
its committing transaction. Never deliberately reuse an incarnation.

There is no separate reset revision/CAS counter. The expected incarnation is the
CAS token; the operation ID identifies a retry. Incarnation is distinct from the
entity epoch and SQLDelight local generation. The product retains one active
local DAG data plane.

Keep `authorityBindingId` and frozen pairing/recovery AAD unchanged. Extend the
publication requirement separately:

```text
canonical endpoint + userId + accountIncarnation + workspaceId + writerDeviceId
```

Discovery is an observation, not permission to rewrite that durable binding.

### 3.2 PostgreSQL ownership

Schema, to be implemented only through new Flyway migrations:

| Owner | Durable fields and rules |
| --- | --- |
| `someday_account_data_incarnations` | PK `(user_id, incarnation)`; `active` or `retired`; creation/retirement timestamps; `legacy` or `incarnation-v1` storage layout; nullable `media_reclaimed_at` for the operator completion gate in section 9.2. |
| `someday_sessions` | Immutable `data_incarnation`. Refresh preserves it. Existing revocation and expiry still apply. |
| `someday_devices` | Mutable enrollment `data_incarnation`; globally claimed device UUID and permanent manual `revoked_at` semantics remain. |
| `someday_entity_workspaces` | Immutable `data_incarnation`. Retain registry rows permanently after content cleanup. |
| `workspace_pairing_invites` | Immutable `data_incarnation` on creation and all transitions. |
| `someday_account_data_resets` | PK `(user_id, operation_id)`; protocol version, expected/new incarnation and commit timestamp. Contains only committed receipts. |

The incarnation table is the **only** source of the current incarnation; do not
also add a current-incarnation pointer to `someday_users`. A partial unique index
permits at most one active row per user. Account creation and reset transactions
must establish exactly one; admission fails closed if there is none. The new
Flyway migration backfills existing accounts and installs an `AFTER INSERT`
user trigger to create G0 atomically for every later account, including direct
SQL fixtures. Do not duplicate this insert in application paths. AdminBootstrap
already uses the normal account-creation repository; test that path as well.

Receipt incarnations reference their account's incarnation rows, with unique
`(user_id, new_incarnation)`. Incarnations do not reference receipts back. This
avoids circular user/pending-operation FKs. Retire the old active row before
inserting the new active row within the same transaction.

Use account-ownership FKs. A session references its immutable account incarnation
and its device ownership separately: changing device enrollment must not cascade
into session, workspace or pairing incarnations. Reset does **not** stamp device
`revoked_at`. A manually revoked UUID can never be revived by reset or enrolled
in another account. Retain minimal global device claims after cleanup.

Entity/media children retain their account/workspace ownership; every access
checks the registry incarnation before reading, replaying or inserting. Recovery
remains one account-current envelope, cleared by reset; PUT validates that its
workspace is current. Recovery revision is independent of account incarnation.

The two new account-only tables follow the existing authentication-table
convention: **no RLS**, explicit authenticated `user_id` predicates and ownership
FKs. Login/refresh do not acquire a new workspace scope. Keep existing forced
workspace RLS and its catalog policy shape unchanged; entity/media queries and
cleanup still predicate both user and workspace. Verify the new schema and
cross-account denial without adding a workspace policy to account-only tables.

Migration/maintenance use transaction-local wildcard scopes for tenant DML.
Ordinary account/content requests never acquire wildcard account scope. The
existing authenticated administrator repository is an explicit privileged exception:
`AdminRepository` currently sets wildcard scopes on checked-out connections.
Preserve pool scope clearing, explicit actor authorization and target predicates;
do not copy this exception into login or ordinary requests. Narrowing all admin
scope lifetimes is separate hardening, not a reset prerequisite.

### 3.3 Local persistence and credentials

Add SQLDelight-owned state for:

- incarnation on the published workspace binding;
- a local reset intent: operation ID, endpoint/user, expected incarnation,
  original workspace/writer, consent target, reconciliation state and whether
  an earlier submission still has an unknown outcome;
- a durable incarnation-mismatch/unknown-outcome gate and explicit offline mode,
  scoped to that local workspace and authority;
- protocol-1 capability memory keyed by canonical endpoint/user, retained across
  workspace replacement and sign-out to prevent accidental legacy downgrade.

The intent/gate is installation state, not synchronized content. It survives
workspace cleanup until reconciliation finishes. It contains no password,
request body, recovery code or key; secure-storage aliases may be referenced.
Media proof remains the existing all-present/all-absent tuple of authority,
workspace and ciphertext digest. Permanent workspace tombstones prevent a
workspace ID from being reused across incarnations of the same account; the
tuple already determines its incarnation. Do not add a media-assets column or
rebuild that table solely for reset. Binding/session and stale-callback checks
still enforce admission. Historical observation storage is deferred (section 7.4).

**Do not rotate or relocate the writer UUID for this feature.** Existing platform
identity storage and immutable `localDeviceId` remain. No writer singleton,
pending writer, legacy-ID mirror or graph reconstruction is required. Stable
identity does not remove the workspace/incarnation checks on queued work.

Version the existing secure-credential encoding. Legacy bindings and credentials
migrate as G0, never as a newly discovered incarnation; media proofs stay unchanged.
New login/registration/refresh credentials use the issuing response's incarnation
header (section 4.1), not a later discovery read or client-parsed JWT.

New credentials alone cannot clear an old binding or reset gate. Account control
and explicit rejoin may use them while old content remains blocked; ordinary
sync must pass the extended `ActiveWorkspaceSessionGuard`.

Numbered local migrations are still needed for this state. End the squashed
baseline phase before adding `1.sqm`: retain frozen `1.db`, create `2.db`, and
replace the no-migrations checker with a real populated v1→v2 upgrade and
fresh-schema comparison behind `verifySqlDelightMigration`. SQLDelight's native
verifier is currently disabled because of its schema-differ limitation; merely
allowing `.sqm` files or running that disabled verifier is not evidence. The
implementation plan owns the test and guide changes. Stage D adds `2.sqm` and
`3.db` for nullable, per-attempt rejoin consent on the gate; `1.db`, `1.sqm`
and `2.db` remain frozen. The current gate verifies populated 1→3 and 2→3
upgrades against a fresh schema 3 database.

Before or with the first migration, require future-schema refusal at the shared
schema-aware JVM driver boundary, not platform PRAGMA/version probing. SQLDelight 2.1.0's
[JDBC lifecycle](https://github.com/cashapp/sqldelight/blob/2.1.0/drivers/sqlite-driver/src/main/kotlin/app/cash/sqldelight/driver/jdbc/sqlite/JdbcSqliteSchema.kt)
does not provide that refusal today. New guards do not retrofit old Desktop
binaries: downgrade or concurrent old/new executables on a migrated profile is
unsupported. Server fencing cannot protect SQLite from an old local executable.

## 4. Control API and idempotency

### 4.1 Discovery, headers and compatibility

Add these bearer-authenticated, `auth`-scope routes, without requiring an
unlocked workspace or device-bound sync credentials:

```text
GET  /account/data-state
POST /account/data-resets
GET  /account/data-resets/{operationId}
```

Stage A ships discovery and receipt reads with its issuance headers, reporting
reset unavailable until the POST implementation is ready. A server must not
advertise protocol 1 and then return a discovery-route 404. Stage B1 adds POST.

No cookie authentication, target-user parameter, reset-list API or cancel route.
All responses/errors use `no-store`. Discovery returns exactly:

```json
{
  "protocolVersion": 1,
  "accountIncarnation": "00000000-0000-0000-0000-000000000000",
  "resetAvailable": true,
  "resetUnavailableReason": null
}
```

When unavailable, the reason is `deployment_not_ready` or
`retired_media_pending`; readiness failure takes precedence. There is no
physical-cleanup progress field. Availability is advisory; POST rechecks it.
Legacy fallback requires all of these compatibility checks: the exact discovery
path on the canonical endpoint returns `404` without protocol-1 evidence; a
same-endpoint `/me` request with the same credential succeeds with a strictly
valid, matching account; and this endpoint/account has never been recorded as
protocol 1. Persist that protocol capability independently of the workspace
binding once valid discovery, an issuance header, or strictly validated typed
error metadata establishes it. A typed error is evidence only after its code,
status and required body/header agreement pass the shared decoder; malformed,
unknown or contradictory metadata is not capability evidence.
Persist error-derived evidence only for an already known canonical endpoint
and immutable account ID. An initial login attempt with only an email address
cannot invent or infer a user ID for capability storage. Do not follow
cross-origin redirects or classify proxy HTML, transport/authentication failures
or decoding errors as legacy. A valid empty legacy route-404 body is allowed.
This is a defined compatibility criterion, not proof of the server's version;
known protocol-1 authorities never downgrade on a later `404`.

Keep existing response JSON field sets unchanged; both Ktor and Desktop's JDK
transport decode them strictly. Keep the JWT payload unchanged and opaque to
clients; do not add `data_gen`. Admission uses signed user/session identity to
reread the immutable session incarnation from PostgreSQL. Old tokens need no
claim-specific fallback; their stored session incarnation must still be current.

Incarnation-aware device/content calls send:

```text
X-Someday-Account-Protocol: 1
X-Someday-Account-Incarnation: <canonical incarnation UUID>
```

A bound content call takes incarnation from its durable binding/session, not
discovery. Headers are preconditions, not credentials. Validate them against
the authenticated session, device, workspace and active incarnation. Legacy G0
callers may omit them; after reset, missing protocol headers on device/content
calls return `426 account_protocol_upgrade_required`, even after fresh login.

Control discovery/status and password login require no incarnation header.
Successful account signup, login, device registration and refresh on the new server return
`X-Someday-Account-Incarnation` from the **session issuing transaction**. A reset
may happen before that response arrives; the header must still describe the
issued session, not the now-current account. Missing/malformed issuance headers
on a known protocol-1 server are errors; only verified legacy-server credentials
default to G0.

POST reset requires protocol 1 and matching body/header expected incarnation.
For a new operation, that incarnation must match session and current account.
An authenticated exact receipt replay is exempt from this latter equality:
new-incarnation credentials may inspect an older operation without reauthorizing
old content. Stale sessions cannot use control routes without fresh login.

### 4.2 Request identity and receipt

Persist a UUIDv4 `operationId` and obtain confirmation before the first POST:

```json
{
  "protocolVersion": 1,
  "operationId": "<UUIDv4>",
  "expectedIncarnation": "<observed UUID>",
  "confirmation": "reset-all-account-data-v1",
  "password": "<entered for this action; never persisted>"
}
```

No user ID, target incarnation, workspace exclusion or storage path is accepted.
Reject duplicate/unknown JSON fields, invalid UTF-8, unsupported protocol
versions, a wrong confirmation literal and noncanonical UUIDs.
Requests, receipts and control error decoding are bounded to 4 KiB. Password
bounds, per-account/per-client budgets and bounded Argon2 concurrency still apply.
Permit at most three newly committed resets per rolling 24 hours per account,
in addition to the storage gate in section 9.2. Exact receipt reads do not spend
that reset budget, but remain subject to ordinary request rate limiting.
The two limits are independent: prompt filesystem reclamation can reopen the
storage gate, while the daily limit still bounds reset/session/receipt churn.

The idempotency key is `(authenticated userId, operationId)`. After strict v1
validation, compare the stored protocol version and expected incarnation directly
for exact replay: operation ID is already the key and confirmation is fixed.
Do not store a `requestDigest` or add a canonical hashing format. Password,
token and locale are not request identity. A future protocol must explicitly
define its identity fields before it can share this receipt mechanism.

Return `200` only after commit, with the same object for exact replay and GET:

```json
{
  "protocolVersion": 1,
  "operationId": "<UUIDv4>",
  "previousIncarnation": "<old UUID>",
  "newIncarnation": "<new UUIDv4>",
  "committedAtEpochMillis": 1000
}
```

There is no `202`, accepted/journaled phase, cleanup status, external journal
encoding or durable server-pending operation. The timestamp is a server-chosen
Unix millisecond value stored in the committing transaction, not a timestamp
manufactured again on replay.

### 4.3 Retry and error semantics

- `(authenticated userId, operationId)` is the idempotency key. Exact identity
  returns its receipt regardless of the old expected incarnation; it never
  starts another reset. Reusing it with a different identity returns
  `409 reset_request_conflict`.
- For a new ID, stale expected incarnation returns `409 account_incarnation_mismatch`;
  unreclaimed earlier media returns `409 retired_media_pending`; failed
  deployment readiness returns `503 account_reset_unavailable`. Check receipt
  replay before these new-operation preconditions.
- Verify a new operation's password outside the account lock; inside it recheck
  enabled user, valid session, unchanged password-hash snapshot and all reset
  preconditions. A changed hash requires reauthentication, not acceptance based
  on stale password evidence. No password check is needed to read a receipt.
- Wrong confirmation password returns `403 invalid_credentials`, not `401`.
  Invalid/expired account auth returns `401 unauthorized`; recognized stale
  auth returns `401 account_session_stale`. Disabled accounts remain
  unauthorized. Limits return `429`; verifier saturation returns
  `503 authentication_busy`. Receipt lookup returns `404` only within the
  authenticated account.
- A connection loss/timeout, including an uncertain SQL COMMIT result, is
  **unknown outcome**. Reauthenticate if necessary and query the same ID;
  never allocate another automatically. Receipt absence permits an exact retry
  with password re-entry within current database history, not a claim that no
  reset ever occurred before a restore.
- If the account lock is not acquired within its bound (section 5.1), return
  `503 account_busy` only after confirming that **this attempt** rolled back
  without issuing COMMIT. For reset it does not prove that an earlier same-ID
  request failed. If rollback/COMMIT outcome is uncertain, do not emit this
  code. Section 7.1 preserves earlier uncertainty across a later definitive
  rejection or lookup `404`.
- After commit, ordinary password login is required because the initiator's
  session is stale too. The in-memory confirmation password may be used for
  that immediate call only; release its reference afterward, or on cancellation,
  failure or unknown outcome. Never retain it while awaiting later reconciliation,
  persist it, or mint a one-time post-reset credential. Otherwise ask for it
  again. Login alone does not authorize registration or local discard.

Keep the generic error object shape, e.g.
`{"error":"account_session_stale"}`. A generic protocol-1 failure carries the
same allowlisted code in `X-Someday-Error-Code`; media HEAD carries the header
with no body. Produce status/body/header through one mapping from Stage A.

| Admission outcome, on every applicable route | Status and error code |
| --- | --- |
| Invalid/expired account session | `401 unauthorized` |
| Recognized session from a retired incarnation | `401 account_session_stale` |
| Current session, mismatching bound/expected incarnation or device enrollment | `409 account_incarnation_mismatch` |
| Current session, retired workspace | `409 workspace_incarnation_retired` |
| Non-G0 device/content access without required protocol | `426 account_protocol_upgrade_required` |
| Account lock wait exhausted with this attempt's rollback confirmed | `503 account_busy` |

Authenticate and reject stale sessions before checking content headers; the
`426` case concerns valid current sessions. Receipt replay keeps its explicit
expected-incarnation exception. The reset-only errors above use the same generic
mapping. These codes have one status each, not a shared changed code across
both `401` and `409`.

Both transports must classify `X-Someday-Error-Code` **before** checking a route's
accepted statuses, decoding its success/business DTO, or handling `404` as empty.
Validate an allowlisted status/code pair and bounded generic body agreement;
raise a typed error before business handling. Unknown, contradictory or malformed
codes, or a missing required header on a known protocol-1 generic error, are
protocol failures, never ordinary expiry or a business conflict.

| Route family | Business response preserved after protocol-error classification |
| --- | --- |
| Entity chunk, manifest, pointer CAS, cleanup and push | Existing structured `409` DTO, with no generic-error header. |
| Media PUT | Existing structured publication-conflict `409` DTO, with no generic-error header. |
| Pairing create/claim/complete/cancel | `409 pairing_conflict` alone permits collision retry/already-used handling; preserve `404 not_found`, `410 expired` and `429 pairing_limit` in their applicable routes. Each is a generic body with matching header. |
| Recovery PUT | Preserve generic `409 recovery_envelope_conflict` and `409 workspace_not_initialized`; only these business branches may retain their existing pending-candidate handling. |
| Recovery GET | Generic `404 not_found` alone maps to no envelope. |
| Media HEAD/GET | Generic `404 media_object_not_found` / `media_object_unavailable`; HEAD maps these to no object using headers without body decoding. GET retains its typed missing response. |

Do not label a structured business-conflict DTO with a header promising the
generic error shape. Legacy status-only business handling is restricted to the
legacy compatibility decision in section 4.1. An incarnation error must never
regenerate an invitation ID, report it already used, clear recovery pending state,
or enter a route-specific strict JSON decoder.

The allowlist also covers existing generic validation/auth/limit errors and
the global `500 internal_error` handler. Pairing completion may remain
best-effort after a committed local join, but an incarnation/session signal
must persist the relevant gate before its cleanup exception is swallowed.

Both `KtorSelfHostedSyncTransport` and `JdkSelfHostedSyncTransport` preserve typed
codes and issuance headers. Exceptions must not retain arbitrary response bodies.
`RefreshingSelfHostedSessionExecutor` retries only ordinary protocol-1
`401 unauthorized`, once after refresh. Incarnation/retirement errors are never
refreshed/replayed; a wrong reset password reaches the verifier once.
`account_busy` is retried later with backoff and never triggers refresh; a
refresh that returns it keeps the stored credentials. Preserve the old
unclassified-401 fallback only for verified legacy servers. Login's existing
`401 invalid_credentials` is not a session-refresh operation.

## 5. Server serialization and the reset transaction

### 5.1 Account admission boundary

Use a PostgreSQL transaction advisory lock shared by every account content and
credential operation. Normal content/control reads and writes take a **shared**
lock, including device re-enrollment, revocations and admin mutations with their
existing row locks. Only reset and purge completion-marker changes take an
**exclusive** lock. Never upgrade a held shared lock to exclusive.

Bound the account-lock wait so waiters cannot pile up on pooled connections.
PostgreSQL queues a new shared request behind any waiting exclusive request,
and media PUT keeps shared admission through S3 publication. Shared admission
therefore waits at most **250 ms**; exclusive admission waits at most **45 s**.
These are lock-wait budgets, not request-duration or upload-drain guarantees.
Multiple admitted PUTs can wait serially on the quota lock, and immutable replay
can require both a conditional PUT and a GET, each with its own 30-second S3
API-call timeout. Reset may therefore time out safely even when storage is
healthy. Apply the bound as a transaction-local lock timeout on the account-lock
statement only; narrower locks keep their current behavior. A timeout rolls back
and returns `503 account_busy`. Check HTTP timeout headroom when changing these
budgets; no cancellation or provider-settlement guarantee follows from them.

Use the two-integer advisory namespace: first key `1396982098` (`0x53444152`,
ASCII `SDAR`); second key is the first four SHA-256 bytes of the lowercase user
UUID's ASCII bytes, signed big-endian int32. Synthetic user
`11111111-1111-4111-8111-111111111111` yields `-1116314971`. Collisions serialize
accounts but never authorize them; queries retain full user-ID predicates.

Pin `READ COMMITTED` before the first transaction statement, including RLS
`set_config` and lock queries. The preflight found that authentication and
media transactions inherited the database default; the prerequisite fix now
pins isolation, and Stage A consolidates it in the shared admission helper.
A pre-lock `REPEATABLE READ` snapshot is unsafe.

Lock order: account → recovery-account → account-quota → workspace → rows.
Take only required locks. `/auth/refresh` locates its account through a token
row: resolve the owner with a non-locking read, take the shared account lock,
then lock the token row and recheck its session and user. Locking the row first
inverts the order and can deadlock with re-enrollment revoking that device's
sessions.

Invitation creation additionally takes a narrow invitation-quota advisory lock
after shared account admission and before counting/inserting invitation rows.
Its purpose is the existing eight-active-invite bound, which count-then-insert
without serialization does not enforce. It never waits for media publication;
do not reuse the media quota lock or make account admission exclusive.

Existing admin mutations have one target account, but the authenticated
administrator may belong to another. Resolve target ownership, then acquire the
actor and target account locks in ascending signed derived-key order,
deduplicating collisions, before narrower locks; use shared mode for both.
Recheck actor authorization and target ownership inside that
transaction. Do not add a hypothetical batch-admin API.

Keep the actor lock: admin cookies use ordinary incarnation-bound sessions, and
actor reset must serialize with final administrative authorization.
This is not just protection of the target device row. Resetting an admin's own
account preserves its role but invalidates its console session; its next request
requires login. Browser admin routes derive incarnation from the stored session,
not native-client protocol headers. Early `requireAdmin` checks do not replace
the locked recheck in a mutation.

Reset supplies a commit barrier. Ordinary logout, revocation, password changes
and disablement instead take effect at the next admission/recheck; requests
already admitted can finish, matching the current authentication model. Shared
locks do not promise that these actions drain every in-flight write. Refresh-token
row locks protect one-time rotation; device row locks protect global claims and
permanent revocation. Other credential state is rechecked at admission; already
admitted issuance may finish, and its returned credentials must still pass current
revocation, disabled-account and incarnation checks on every subsequent request.
Do not add a new session/user row-lock order implicitly to strengthen that contract.

Inside content admission, reread account, session and device and require:

```text
request incarnation == immutable session incarnation
session incarnation == active account incarnation
device enrollment == active incarnation (for device-bound operations)
workspace absent, or its immutable incarnation == active incarnation
account/session/device enabled, unexpired, not revoked; scopes/writer valid
```

Check before immutable replay, genesis CAS, checkpoint creation, recovery replay
or pairing transition. An existing retired registry row is never "missing":
return `409 workspace_incarnation_retired`. Fence **both** entity registry
creation and `SystemV3MediaRepository.putObject → ensureWorkspace`, including
the case with no entity epoch yet.

Split scope setup from registry insertion: `SyncV2Repository` currently inserts
the registry row inside `selectWorkspaceScope`, before the business block takes
locks. Required order is `READ COMMITTED → scope setup → account admission →
authority/workspace check → narrower locks → registry insert/replay/write →
commit`. Adding a check only inside the old business block is too late. Apply
the same order to media PUT's registry path.

Pull's `touchDevice` is also a write: the current route invokes it after the
repository returns, using only a device UUID. Move it under admission and bind
the update to captured account/incarnation/enrollment, so an old request cannot
touch a device after it has been re-enrolled into a newer incarnation.

Repository methods receive authenticated context, not just unchecked user IDs.
Route authentication is early rejection, not commit authorization. Hold admission
through repository commit. Cover entity reads/writes, media PUT/HEAD/GET,
recovery GET/PUT, all pairing transitions, session issuance/refresh, device
operations and account-sensitive admin mutations. Refresh never changes a
session's incarnation. Login verifies password outside the lock and rechecks
account/password state while issuing its incarnation-bound session inside it.

Control reads require current account authentication but no usable workspace.
There is no reset-pending exception and no reset-specific `423` behavior.
Use bounded bodies, DB lock/statement timeouts and storage timeouts. Read the
bounded request body before taking the account lock.

The complete route classification is below. S means shared account admission;
X means exclusive. Existing narrower row/workspace locks still apply. Device
listing retains owned historical enrollments so they can be manually revoked;
listing a device never grants it current content authority.

| Route or operation | Lock | Incarnation/authority check |
| --- | --- | --- |
| Health and public admin-login page | None | No account authority. |
| Account creation and AdminBootstrap | Creation transaction | Atomic G0 creation; any subsequent issuance uses current state. |
| Password login and admin login | S at issuance | Password work outside lock; enabled user/hash rechecked; issue current incarnation. |
| Refresh | Owner lookup, then S, then token row | Immutable session incarnation, account/device/session status. |
| Logout and `/me` | S | Current account/session; no workspace required. |
| Device list | S | Current account/session; historical enrollment is not authorization. |
| Device registration/re-enrollment | S + device row | Protocol/expected incarnation, current login authority, ownership/permanent revocation. |
| Device revocation | S + existing row locks | Current actor and owned target; historical enrollments may be revoked. |
| `/sync/v3/capabilities` | S, short | Current sync session/device and protocol; no workspace. |
| Every entity read/write/replay | S through commit | Current session/device/protocol/workspace before insertion or replay. |
| Media PUT | S through publication/commit | Same, before `ensureWorkspace`. |
| Media HEAD/GET | S for object capture only | Same; release connection before blob IO. |
| Pairing create/claim/complete/cancel | S | Current session/device/protocol and invitation incarnation. |
| Recovery GET/PUT/replay | S | Current session/device/protocol; PUT workspace current. |
| Data-state and receipt GET | S | Current account auth; no workspace/header required. |
| Reset POST | S precheck; separate X commit | Current auth, expected CAS and receipt-replay exception. |
| Admin authenticated reads | Actor S | Current admin session; aggregates are observations, not content authority. |
| Admin account/session/device mutations | Actor + target S in key order | Recheck actor admin authority and target ownership; retain row locks. |
| Purge SQL batches | S, bounded | Still retired, explicitly owned target; preserve claims/receipts. |
| Purge marker changes | X, short | Still-retired identity and unchanged verification context. |
| Purge blob IO | No long DB lock/connection | Proven retired roots; revalidate before recording completion. |

### 5.2 One atomic logical reset

1. Under short shared admission, authenticate and inspect any existing receipt.
   Return exact replay or identity conflict without new-operation side effects.
2. If absent, verify the password outside the lock using the bounded verifier.
3. Begin one `READ COMMITTED` transaction and take the exclusive account lock.
   Revalidate authentication and check the receipt again. If a concurrent reset
   made the session stale, return the typed incarnation error; a fresh login can
   retrieve the receipt. With valid current authentication, return an existing
   exact receipt. For a still-new request, validate password evidence, expected
   incarnation, readiness, storage gate and rate limit.
4. Retire the active incarnation and create its random successor. Delete the
   account-current recovery envelope. Make old pairing records inaccessible by
   incarnation immediately; bounded deletion of their payloads may follow.
5. Insert the committed receipt and initialize the retired incarnation's media
   reclamation gate as incomplete. Commit all changes atomically.
6. Return the receipt. Large content deletion does not run in this transaction.

Capture `retired_at` using database wall-clock time **after** acquiring the
exclusive lock and draining preceding admitted transactions. Do not use a
transaction-start timestamp taken before a long lock wait; section 9.2 measures
its minimum S3 settling period from this value.

Old sessions lose authority by incarnation mismatch; do not iterate every device
or stamp manual revocation to establish the barrier. Later bounded cleanup may
remove old session secrets and content. A second reset with the same expected
incarnation conflicts; an exact ID retry returns the original receipt.

The new incarnation initially has no workspace, key or recovery envelope. Clients
explicitly create a fresh workspace or join one established by another device.
Do not add a single-workspace server constraint or elect keys on the server.

A known rollback leaves no receipt or partial transition. An uncertain commit
response uses section 4.3, not an asynchronous reset reconciler. Normal lock
contention is not a durable product state or a reason to disable the account.

### 5.3 Blob IO and reset races

Media PUT retains shared account admission and its quota transaction through
blob publication and SQL commit, preserving blob-before-metadata ordering.
Reset waits for that transaction. A timed-out provider PUT may still complete
after SQL rollback; it creates only an orphan in its captured old namespace,
never current-incarnation metadata. Cleanup must account for this uncertainty.

HEAD/GET instead captures account/incarnation/workspace, object key, expected
size and digest in a short admission transaction. Release the lock **and pooled
connection before blob IO**. Read only that captured object, bounded by
`MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES` (4 MiB original plus metadata/encryption
overhead), without resolving a newer path. Slow downloads cannot occupy the
database pool for their duration.

If cleanup races a failed read/verification, recheck the captured incarnation in
a new short transaction. If retired, return `409 workspace_incarnation_retired`, not
corruption requiring repair/republication. Otherwise retain ordinary missing,
corrupt or storage-error handling. An already-admitted successful read may
return its verified bytes; every subsequent request needs fresh admission.

## 6. Stable devices, pairing and recovery

### 6.1 Explicit re-enrollment, not revived sessions

After reset, obtain an unbound account session through ordinary password login.
It belongs to the current incarnation and permits account/device management,
not synchronization. In the explicit rejoin flow, call existing
`POST /devices/register` with protocol/incarnation headers and the **same** stable
device UUID. Request/response JSON stays unchanged.

Under shared account admission and the device row lock, validate a current-
incarnation unbound login session with `devices` scope (or its same-incarnation refresh), expected
incarnation, global device ownership and absence of manual revocation. If its
enrollment is old, move just that device row to the active incarnation and issue
a new device-bound session. Never use a retired device session to do this;
post-reset login is necessary. Current-incarnation ordinary registration keeps
its existing authorization rules.

Re-enrollment may revoke prior sessions for that UUID as registration does today;
it must not update their stored incarnations. Registration response loss is
retried with the same device ID, not a newly generated one. A concurrent reset
either happens before registration and rejects it, or makes its newly issued
session stale afterward.

The client asks for local-discard consent and durably gates the old workspace
before starting rejoin. Registration itself does not replace local data. A
failed/cancelled Pair or Recover preserves the gated old workspace. A new token
with the reused UUID still cannot publish an old workspace: its registry entry
is an immutable retired tombstone, and the local incarnation guard rejects it.

**Password renewal is not explicit rejoin.** For a bound workspace,
`SelfHostedSetupService → loginAndReconnectBound` must compare the login's
issuing incarnation with the persisted binding **before** calling registration.
On mismatch, persist the gate and return the typed mismatch
state without registering or overwriting the binding. Login credentials may be
used for account control only. A match permits ordinary renewal with the bound
incarnation. Only the consented rejoin flow sends a different target incarnation
after persisting its discard target and gate. Server races remain fenced even
when the preliminary login comparison matched.

### 6.2 Pairing

Incarnation-scope every remote pairing transition. A pre-reset invite/claim
cannot complete or replay in the new incarnation. Keep token encoding, checksum,
HKDF domains, QR payload, AAD and encrypted package unchanged.

Scope content-availability aggregates too: the active invitation count uses
user **and current incarnation**, state and expiry. Unexpired retired invitations
must not consume the new incarnation's eight-invite allowance. Authentication
budgets and the reset rate limit remain account-wide across incarnations.

Keep the invitation incarnation column. Eight is a bound on unexpired active
invitations, not all stored rows: completed/cancelled rows and expired rows can
accumulate. Deleting every invitation inside reset therefore has no fixed
eight-row bound. Incarnation admission gives immediate invalidation without
making that unbounded deletion part of the logical commit.

Persist the target incarnation with the local attempt. After explicit discard
consent, log in/re-enroll as needed, then claim and install the new workspace in
**one** local replacement. Do not first create an empty workspace merely to
register a different writer, or require a second discard confirmation for that
intermediate workspace. Existing per-attempt confirmation before remote claim
remains mandatory.

Recheck account incarnation before installation. If reset commits just after the
check, local installation can retain only the attempt's captured incarnation;
the next request is fenced. Never relabel a received package with a later
discovery value. There is no distributed SQLite/PostgreSQL commit promise.

### 6.3 Recovery

Reset deletes the account-current recovery envelope. Old-incarnation GET/PUT
and exact replay fail before envelope logic. Recovery revisions may restart at
1 because CAS identity includes `(account, incarnation, revision)`.

Keep code formatting, KDF, AAD and portable/encrypted bytes unchanged. An old
code may decrypt a saved historical envelope but cannot authorize its retired
workspace on the current server. A fresh workspace requires independent recovery
setup; Pair/Recover of an already-created current workspace preserves that
workspace's authority. A recovery `404` never clears an incarnation gate.

## 7. Client workflow and local commit

### 7.1 Confirmation and unknown outcome

Entry: **Settings → Sync/account → Danger zone → Reset account data**. It works
without an unlocked old key. Identify server/account, all-workspace scope,
disconnection of all devices, separate local replacement, operator retention,
backup rollback limitation and reauthentication. Require password re-entry and
a localized confirmation phrase; the wire confirmation literal stays fixed.

Offer local export where readable, warning that portable export omits image
bytes. Disable duplicate submissions. Never infer discard consent from an
empty, blocked, locked or unhealthy workspace.

State that password possession alone authorizes immediate remote reset, without
undo or a cooling-off period. Export/recovery codes cannot restore retired
images through the normal account flow; recovery may depend on operator backups
or surviving local copies. Do not imply every image is necessarily erased.

```text
Ready → Confirming → IntentPersisted → RequestInFlight / OutcomeUnknown
                                          |
                                 committed receipt verified
                                          v
                              RemoteCommittedLocalPending
                                          |
                          login + explicit current-incarnation setup
                                          v
                                    LocalReady
```

Persist the intent under `WorkspaceLifecycleCoordinator` before POST. Freeze
product mutations and data-plane work for that workspace; allow read-only view
and export. Every worker rechecks the durable gate inside the shared coordinator
after restart. Do not hold a UI coroutine mutex while indefinitely waiting on
HTTP/status checks.

A definite rejection/rollback, including `account_busy`, clears only its
matching intent **if no earlier submission remains unresolved**, and unfreezes
only if its original incarnation is still valid. Persist earlier uncertainty
before retrying; a restart with an in-flight submission is unknown. A later
attempt's rejection or status `404` cannot erase that uncertainty: the earlier
request may still commit. Keep the same operation ID for reconciliation.
Incarnation mismatch stays gated even when this reset request was rejected.
Unknown outcomes display “结果待确认 / Checking reset outcome”, never “failed,
old data unchanged”. Failed local intent persistence stops POST; a broken
database is not permission to delete it silently.

Provide **Keep this copy and continue offline / 保留本机副本并离线继续编辑** as
an explicit exit from indefinite local read-only mode. Under the lifecycle
coordinator, persist offline mode and retain the unresolved intent before
allowing local mutations. Remote data-plane access remains disabled; account
control/status reconciliation remains available. Explain that this does not
cancel the remote request, restore server data or prove failure. Do not start
another reset while the old outcome is unresolved.

Offline edits invalidate any earlier local-discard consent. A later receipt
can update the remote outcome but cannot automatically replace this copy;
reconnection/replacement requires reconciliation and fresh discard consent for
the exact current workspace. A timeout plus `404` and an unchanged incarnation
is never a definitive negative result: an earlier submission may still commit.

Verify receipt identity, original incarnation and current account before local
continuation. If discovery differs from the receipt's target, require fresh consent for
the observed current incarnation. Withdrawing local consent cannot undo a
committed remote reset. Changing the selected account/workspace invalidates
the old discard target.

### 7.2 Transactional replacement with the same writer

Reuse existing key staging and `WorkspaceLocalReplacementV2` under the existing
`WorkspaceLifecycleCoordinator.exclusive` / `productAccess` boundary. Register
the stable device against the target incarnation as necessary, without binding
its new credentials to old local content.

For a fresh start, stage a new master key and workspace ID. For Pair/Recover,
stage the verified joined workspace. One SQLDelight transaction removes every
old local generation, DAG, projection, outbox, protocol, media and source-import
row, then installs the target workspace, unchanged writer, captured incarnation
and local-completion state. Preserve installation settings and the reset intent
until reconciliation finishes, and preserve protocol capability from section
4.1. Do not let an existing fresh-workspace helper
silently attach old credentials.

Failure before local commit preserves the previous database/key, still gated
when its server incarnation is retired. Reuse the pending-alias cleanup mechanism;
remove old files/aliases only after commit, with best-effort retry. File deletion
failure cannot turn a committed replacement into a replacement failure.

Keep the current service graph and coordinator. Queued sync, controller and
background work must capture/check workspace and incarnation inside that boundary
before mutation or applying results; resolving a new workspace at callback time
is insufficient. Invalidate old UI snapshots/pending recovery or pairing state
on replacement. Stable writer identity avoids rebuilding identity-bound services,
not these stale-work checks.

Secure credential writes and SQLite are not one transaction. Persist the gate
first and never admit remote content/data-plane work until credential, workspace
and incarnation match. Explicit local offline editing follows section 7.1.
On restart resume from committed local state; a missing credential leads to
reauthentication, not relabeling or another reset. Registration, publication and
recovery setup are independently retryable after replacement.

### 7.3 Other devices and platform UX

Incarnation mismatch or `workspace_incarnation_retired` enters durable
`ResetRequired`.
Use neutral copy: “本机数据代际与服务器不一致 / Local and server data incarnations
differ”, not an unconditional claim that the account was reset. Never bypass
the gate through refresh, empty-recovery fallback, epoch rollback repair or
automatic workspace initialization. New credentials do not clear it. Keep
permanent `device_revoked` distinct: reset/rejoin cannot revive that UUID.

Keep old local content read-only/exportable initially. Offer the explicit local
offline-edit mode from section 7.1, preserving the network gate and any unresolved
intent, or separate consent to discard it and create, Pair or Recover
in the current incarnation. Do not remotely erase offline edits. An old client
may lack this UI but must still be denied by the server. A `503`/timeout alone is
not evidence of reset: use bounded retry for the same attempt, preserving its
uncertainty.

All three platforms share this state machine, including Android background
startup and reminders. Controller actions are suspend; IO alone moves to a
background dispatcher and UI state returns to the UI coroutine. Constructors
and derived UI builders stay cheap and pure. Local replacement cannot bypass
confirmation by entering through a worker, notification or import action.

### 7.4 Historical rollback detection is deferred

V1 does not persist retired/superseded incarnation observations or a separate
`ServerRollbackSuspected` state. Ordinary mismatches preserve the local copy,
offer export and require explicit per-attempt discard consent; random UUIDs
cannot be ordered or used to distinguish reset from restore. Receipt validation
and the unknown-outcome rules remain mandatory.

This is deliberately less protective than historical detection. A database
restore matching the local binding, or removing a previously committed receipt,
can be undetectable to this client. Even history-based checks would protect
only observed histories, not fresh clients or old clients matching a restored
server. Such a mechanism can be a later feature with its own persistent state
and acknowledgement exit; it is not required for R1–R10 and is not a v1 gate.

## 8. Quota and incarnation-specific blob layout

After commit, ordinary media quota sums only active-incarnation workspaces.
Retired storage is tracked separately for operations. Active quota release is
not proof of deletion; section 9.2 blocks repeated fresh quota allocations while
earlier retired media remains.

Keep G0 blob keys unchanged; nonzero incarnations use separate private namespaces:

```text
S3 legacy: media/v1/<userId>/<workspaceId>/<mediaId>.bin
S3 new:    media/v1/.incarnations/v1/<userId>/<incarnation>/<workspaceId>/<mediaId>.bin

FS legacy: <root>/<userId>/<workspaceId>/<existing shards>/object.bin
FS new:    <root>/.incarnations/v1/<userId>/<incarnation>/<workspaceId>/<existing shards>/object.bin
```

`MediaBlobKey` receives validated identity/layout from the repository, not client
paths. Public routes, ciphertext, immutable replay and image bounds are unchanged.
No old/new path probing or fallback. The entire G0 account prefix becomes retired
on first reset, including orphan objects with no SQL workspace row. Shared startup
probe keys must sit outside all account cleanup roots.

The S3 child namespace inherits the existing `media/v1/` policy boundary under
the documented profile, including active-object retention and lifecycle-rule
exclusions. It has no separate lifecycle in v1. Do not require a new sibling
prefix grant or R2 lock rule. Still verify actual nested-path access and any
deployment-specific policy exceptions; a probe at one legacy key does not prove
all descendant keys or filesystem directory permissions work.

## 9. Operator cleanup, storage limits and backup recovery

### 9.1 Operator-owned SQL and blob cleanup

One operator command, `purge-retired-account-data`, owns retired SQL **and** blob
cleanup, using separate maintenance credentials and default dry-run. There is no
background SQL worker, continuously required deletion service or reset reconciler.
The HTTP runtime keeps its PUT/HEAD/GET-only API, without LIST/DELETE or
upload-failure compensation.

This command and reclamation certification are Stage B2, separable from the
first logical-reset release (B1). B1 already enforces the incomplete marker and
blocks repeat resets. Until B2 is delivered, no supported path may set that
marker or promise reclamation. Storage protection, startup checks and backup
warnings remain prerequisites for the first reset, not deferred maintenance work.

Delete retired SQL content in bounded, restartable batches under shared account
admission on the command's own connections; only section 9.2's marker changes
take the exclusive lock. Recheck retirement and explicit ownership for every
batch; respect existing RESTRICT FKs. Delete changes/replay rows before objects,
then checkpoints/epochs, media metadata and pairing payloads. Retain workspace
tombstones, incarnation history, device claims and receipts. Remove obsolete
session secrets and scrub device labels when retained only as claims; never
delete or scrub a device's newer enrollment as old-incarnation cleanup. SQL
cleanup can proceed while blob retention blocks deletion; it cannot certify
media reclamation. Retained incarnation/layout records still identify whole roots.

The command derives exact retired roots from validated user/incarnation/layout,
not arbitrary SQL/request paths. Recheck database identity and retirement before
bounded batches. On S3 include current objects, noncurrent versions and delete
markers; a delete marker is not physical removal. On filesystem reject symlinks
and path escapes with no-follow traversal, including temporary-file cleanup.
Never bypass retention or shorten shared locks. Operator output distinguishes
permission failure, retention block and incomplete verification without exposing
content or secrets. It is not part of the user reset receipt/API.

### 9.2 Bound repeated resets before releasing another quota window

Under the exclusive account lock, reject a new reset if **any** earlier retired
incarnation has `media_reclaimed_at IS NULL`. This is `409 retired_media_pending`,
not rate limiting. Exact receipt replay and ordinary current-incarnation sync
remain available. The first reset is possible without purging its current media.
Once it commits, another reset waits for that retired namespace to be reclaimed.

Only the operator command may set `media_reclaimed_at`. It must cover the entire
namespace, versions, markers, temporary files and unindexed orphans; SQL deletion
or zero indexed bytes is insufficient. There is no durable ledger of failed PUTs:
an empty query cannot prove that remote publication has stopped.

V1 uses these explicit backend assumptions:

- **Filesystem:** publication is synchronous. After admitted publishers have
  drained, the retired namespace can be cleaned and completely scanned without
  a timed settling period.
- **S3:** final certification scanning starts no earlier than **24 hours after
  `retired_at`**. This exceeds the current 30-second whole-call/10-second attempt
  timeouts, but those timeouts do not cancel remote writes. The operator must
  certify the assumption that all pre-retirement publications, including ones
  whose client timed out, have settled and are visible to complete listing by
  that deadline. The profile must provide complete, consistent enumeration of
  current objects, versions and delete markers. S3 does not guarantee the
  24-hour bound; unsupported or violated assumptions leave reclamation uncertified.

Both profiles require no external writer or suspended old publisher able to
resume into the retired prefix. Stop/drain affected processes before maintenance
if a failed connection/process makes this uncertain. S3 certification also
assumes a trustworthy retirement timestamp and clock; clock uncertainty or
restore requires revalidation, not skipping the wait. Neither repeated empty
listings before the deadline nor an SDK timeout substitutes for these conditions.

After the applicable barrier, perform a fully paginated namespace scan. A
nonempty scan cleans eligible objects but does not certify; a subsequent complete
empty scan may certify. Retention blocks, errors, partial scans and interrupted
runs never establish completion. This is conditional operational evidence, not
proof of provider finality or a new asynchronous publication state machine.

Storage work runs outside a long database transaction. An executing command
clears any prior marker for its target under the exclusive account lock before
auditing, including previously certified targets; dry-run remains read-only.
Record completion under that lock only for the same still-retired incarnation and
unchanged verification context. On interruption the marker stays empty. Objects
appearing after certification violate the profile assumption: report the violation,
leave the marker cleared and require operator disablement/revalidation before
recertifying, not another blind wait. Backup restoration invalidates attestations
as specified in section 9.4.

Under these assumptions this bounds **unreclaimed incarnations**, avoiding a
fresh quota allocation on every reset during retention. It is not an absolute
bound on provider billed bytes: existing publication orphans, object versions
and backups are outside the indexed active quota. Deployment capacity/retention
controls remain necessary. Do not promise “at most twice the quota” as a
physical-storage guarantee.

### 9.3 Retention profiles and enablement

Prefer finite media retention compatible with coordinated backups, and a
maintenance identity able to remove retired versions after retention expires.
Retention expiry must not automatically expire active media or grant the HTTP
runtime deletion permission. Validate actual provider APIs/policy on disposable
resources; PUT/HEAD/GET certification alone does not certify cleanup.

The existing [R2 profile](../../guides/self-hosting-external.md) and
[managed-storage gate](../../guides/managed-storage-profile-gates.md) indefinitely
lock `media/v1/`, including its new `.incarnations/v1/` child. Do not weaken that
shared protection for one account. The documented profile does not need a new
lock rule merely to enable a **logical first reset**. Verify its actual policy
and lifecycle exclusions still cover the child; create/read probes do not prove
deletion denial. No policy change is applied automatically.

An old namespace containing indefinitely locked objects stays unreclaimed;
further resets remain blocked. An actually empty namespace may be certified
only through the same settling/listing rules once B2 exists; therefore “one reset
per account” is not unconditional. This profile cannot advertise eventual
physical deletion. The operator
checklist names that limitation. Client confirmation always warns that retention
may prevent further resets indefinitely; it must not infer purge capability
from `resetAvailable`. A different retention/permission profile requires a
separately approved migration, not silent policy changes by the command.

`ServerConfig` reads `SOMEDAY_ACCOUNT_RESET_ENABLED`: absent means `false`; only
the exact values `true` and `false` are valid. Any other value fails startup in
both local and production modes. Deployment templates and upgrades default to
disabled and must never turn it on automatically. Disabling it blocks new resets,
not receipts, incarnation fencing or current-incarnation sync.

Setting it to `true` is the operator's declaration that incarnation fencing,
backup guidance, storage limits, legacy/nested-path active-object protection and the
retention profile have been reviewed. For S3 reclamation it also accepts section
9.2's settling/listing assumptions; a logical-only indefinite-retention profile
must explicitly accept that reclamation and repeat reset remain unavailable.
Runtime probes cannot verify deletion denial or future provider completion.
Those require separately recorded disposable-profile certification and operator
attestation, not a misleading automated readiness test. A false declaration is
an unsupported deployment, not a condition the server claims to detect.

Extend the existing startup probe to exercise both layouts under the same
protection boundary, using bounded reusable system keys outside all account
cleanup roots:

| Namespace | Readiness evidence |
| --- | --- |
| `media/v1/` | Existing bounded immutable media probe and missing-versus-denied behavior. |
| `media/v1/.incarnations/v1/` (FS `.incarnations/v1/`) | Conditional create, equal replay, unequal replay rejection, actual-byte HEAD/GET validation, missing-versus-denied behavior at the actual new layout. |

The legacy probe stays mandatory. The nested-layout probe is mandatory at
startup when reset is enabled **or any account has an active nonzero incarnation**;
failure rejects startup, even when reset was subsequently disabled. Query this
condition from the authoritative database before serving traffic. Recheck before
enabling reset. While every account remains G0 and reset is disabled, failure of
an optional nested-layout probe must not take legacy service down. Runtime loss
of required readiness blocks new resets, and actual storage failures remain
fail-closed. Prefix nesting removes a default policy migration, not this actual
path check. No control-journal prefix, independent journal volume or host-loss
acknowledgement setting is introduced.

Maintenance permissions stay separate. On filesystem the runtime has no delete
code/API, but directory write access is not an OS-level no-delete guarantee.
An unavailable cleanup command blocks a subsequent reset, not a committed
reset or active-incarnation access.

### 9.4 Historical backup restoration is an explicit rollback

Keep PostgreSQL and media as one recovery unit under the existing
[backup guide](../../guides/server-backup-and-recovery.md). V1 has no external
retirement evidence to replay. Restoring a pre-reset database can restore its
old incarnation, data, sessions/refresh tokens, device authorizations, pairing
records, recovery envelope and old password/revocation state. Retained keys may
make that data usable again. This is not equivalent to returning encrypted
bytes without authority.

Operators must keep ingress and maintenance closed during restore, identify the
recovery point and communicate its loss window before reopening. Reconcile
the chosen database/media set and normal integrity checks; stop and drain cleanup
commands before restoring, rather than pausing a deletion plan for later reuse.
Clear/revalidate all retired-media completion attestations before allowing new
resets, because restored blobs or versions may contradict them. Do not reuse a
pre-restore deletion plan: a formerly retired prefix can be active in the
restored database.

Clients keep their persisted incarnation. Any mismatch after restoration remains
blocked pending explicit reconciliation; they must not downgrade bindings or
automatically republish old outboxes. Section 7.4 deliberately defers historical
rollback detection. Unseen UUIDs alone cannot
distinguish a later reset from rollback, and restored SQL alone cannot detect a
missing later reset. A lost receipt therefore cannot support a universal “reset
never happened” claim.

Ordinary standalone recovery remains possible without a separate live journal.
There is no v1 promise to preserve reset across recovery-point rollback, prevent
old clients whose incarnation matches a restored snapshot from reconnecting, or
erase historical backups before their retention expires. The operator runbook
must state these limits alongside existing password/revocation rollback limits.

## 10. Compatibility and acceptance boundary

- New server + untouched G0 + old client: unchanged existing JSON and ordinary
  data operations. JWT payloads stay unchanged; HTTP response headers are additive.
  A brief `503 account_busy` can occur while an exclusive account operation
  runs; it uses the existing error shape.
- New server + reset account + old client: password login may work, but
  device/content access requires protocol 1 and correct incarnation.
- New client + old server: section 4.1's legacy compatibility checks disable reset; ordinary
  existing sync remains available. Other failures do not imply unsupported.
- New client + new server: exact incarnation binding, typed errors and durable
  local gate on Android, iOS **and Desktop/JDK**.
- Migrated local profile + old binary: unsupported. Future-schema guards protect
  only builds containing them; characterize older Desktop behavior on copies.
- Pre-incarnation server image + migrated server schema: unsupported. Preserve
  Flyway future-version startup refusal; never bypass it for rollback.

Ship the server boundary with reset disabled, then all supported clients, then
enable only on verified deployments with operator approval. Publication,
migration and deployment are separate authorized actions. Rolling forward with
new resets disabled must preserve committed receipts and incarnation fences.
All pre-incarnation server processes must be drained before enabling reset;
startup schema refusal alone cannot fence a process already serving old code.

Keep existing pairing/recovery/media vectors unchanged and add identity, lock,
credential and header vectors. The sole failure/race matrix, rollout stages and
owning spec/guide amendments are in [the implementation plan](implementation.md).
