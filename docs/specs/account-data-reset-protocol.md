# Account data incarnation protocol

Status: implemented server admission, logical reset, operator reclamation and
shared client protocol/state and product UI integration (Stages A/B1/B2/C/D).
Coordinated rollout remains in the
[accepted implementation plan](../plans/account-data-reset/implementation.md).
Reset defaults off and deployment templates never enable it automatically.

This specification owns account incarnation admission and its HTTP metadata.
Entity DAG generations are an independent local synchronization concept.

## Identity and admission

An account has exactly one active opaque UUID incarnation. Existing and newly
created accounts start at G0 (`00000000-0000-0000-0000-000000000000`). Flyway V10
atomically backfills existing records and installs a user-insert trigger for
new accounts. Sessions, workspace registry rows and pairing invitations retain
an immutable incarnation; a device has a mutable current enrollment. Workspace
IDs remain permanently claimed within their account. Manual device revocation
and another account's device UUID claim cannot be bypassed by re-enrollment.

Every authenticated repository entry takes an authenticated request context,
acquires a transaction-scoped shared account advisory lock, and rechecks current
session, user, device, scopes and applicable incarnation/workspace preconditions
before registry creation, immutable replay or other content access. Password
issuance rechecks the verified password-hash snapshot under that boundary.
Refresh resolves its account before admission, then locks the refresh-token row
and rechecks authority. Issued sessions capture the current incarnation inside
the same transaction; responses never retag credentials from later discovery.

Transactions set `READ COMMITTED` before their first SQL statement. Account
lock keys use namespace `1396982098` and the signed big-endian first four bytes
of SHA-256 of the canonical account UUID ASCII. Multiple keys are deduplicated
and sorted. Shared acquisition has a total 250 ms wait budget; the helper also
uses a 45 s exclusive acquisition budget for reset/marker operations.
These budgets apply only to account-lock statements and do not bound upload or
quota-lock duration. Only a confirmed rollback of an account-lock timeout
produces `account_busy`. An uncertain transaction outcome remains a server
failure. Ordinary revocation may follow already-admitted work; it does not add
an account-wide exclusive barrier.

| Route family | Admission and additional boundary |
| --- | --- |
| Signup/password login, including admin login | Create G0 atomically; issue under shared admission after password snapshot recheck. |
| Refresh | Shared account lock before refresh-token row lock; session incarnation cannot change. |
| Logout, `/me`, `/devices`, discovery and receipt reads | Shared account admission; current session required. |
| Device registration/re-enrollment and revocation | Shared admission plus existing device row lock; historical re-enrollment requires an unbound current-incarnation login. |
| Capabilities, entity, media, pairing and recovery | Device-bound `sync` admission before read, replay or mutation; workspace/incarnation checks where applicable. |
| Pairing create | Additional narrow account invitation lock serializes current-incarnation count and insert. |
| Reset commit | Exclusive account admission; current auth, password snapshot and every new-operation precondition rechecked. |
| Purge SQL batches / marker changes | Shared admission for bounded SQL; exclusive admission for audit and certification markers. Blob IO holds no long DB transaction. |
| Admin reads | Shared actor admission and current administrator role; authorized aggregate scope. |
| Admin mutations | Canonically ordered shared actor/target locks, current actor authorization and target-owner recheck. |

Media HEAD/GET capture authorized metadata and the physical key, then release
the transaction and connection before blob IO. An already-admitted read may
finish; a missing/corrupt object rechecks admission before reporting corruption.
All data SQL keeps explicit user/workspace predicates. Incarnation and receipt
tables follow existing authentication tables: no RLS, explicit user predicates.
Existing forced workspace RLS remains unchanged. Ordinary account/content
requests never use account wildcard scope. Authorized admin queries use it
transaction-locally; migrations and operator integrity verification also have
controlled wildcard access.

## Headers and errors

Successful signup, login, registration and refresh return
`X-Someday-Account-Incarnation` for the issued session without changing existing
successful JSON or JWT field sets. Incarnation-aware device/content calls send
both `X-Someday-Account-Protocol: 1` and
`X-Someday-Account-Incarnation: <canonical lowercase UUID>`. These are
preconditions, not credentials. Duplicate, incomplete or malformed headers are
invalid. G0 callers may omit both headers; current nonzero device/content calls
require them even after fresh login. Account control calls do not require them.

Generic errors use `X-Someday-Error-Code` and JSON `{"error":"<code>"}`. HEAD
has the same header/status and no body. Existing route-specific business 409
responses keep their existing DTO and do not acquire a generic error header.
Consumers must inspect the generic error header before decoding business DTOs.

| Status | Code | Meaning |
| --- | --- | --- |
| 401 | `device_revoked` | The session’s device enrollment was manually revoked. |
| 401 | `account_session_stale` | The stored session belongs to an earlier incarnation. |
| 409 | `account_incarnation_mismatch` | Device enrollment or supplied incarnation does not match current authority. |
| 409 | `workspace_incarnation_retired` | The requested workspace belongs to an earlier incarnation. |
| 426 | `account_protocol_upgrade_required` | Required protocol metadata is absent or the version is unsupported. |
| 400 | `invalid_request` | Protocol metadata is malformed. |
| 403 | `invalid_credentials` | The new reset operation’s confirmation password is incorrect. |
| 409 | `reset_request_conflict` | An existing operation ID has different request identity. |
| 409 | `retired_media_pending` | An earlier retired incarnation has no valid reclamation marker. |
| 429 | `reset_rate_limited` | Three resets already committed in the rolling 24-hour window. |
| 503 | `account_reset_unavailable` | Reset is disabled or required storage readiness failed. |
| 503 | `account_busy` | Account-lock wait expired and rollback is confirmed. |

Other authentication, permission and business errors retain their existing
status/code semantics. Device revocation now has the explicit `device_revoked`
code, still with status 401. Shared clients distinguish these failures before
route-specific business handling, including the routes that accept business 409s.

## Shared client authority and failure handling

Credentials capture the incarnation from their own issuance response. The
versioned secure-storage envelope retains that value and protocol version;
legacy records decode as G0 with unknown capability. Discovery never retags an
existing credential or published workspace. Ordinary bound login verifies the
issuing account and incarnation before registering the existing writer. A
mismatch preserves the local copy and performs no registration.

Ktor and JDK inspect and validate generic error metadata before decoding
successful or business DTOs. Generic/control JSON is bounded to 4 KiB and
requires strict UTF-8, exact fields, unique keys, and matching header/body/status.
HEAD uses only the header. Unknown, duplicate, malformed, contradictory or
required-but-missing metadata fails closed. Redirects are not followed. Fixed
error classifications replace arbitrary response text in exceptions.

Only ordinary `401 unauthorized` refreshes automatically, once. Stale-session,
incarnation and revoked-device failures do not refresh; account contention does
not clear credentials. Within the shared lifecycle boundary, refresh rechecks
the credential store after its network response and cannot change the captured
incarnation or overwrite a newer/cleared session. The store is not a standalone
compare-and-set API for uncoordinated writers. Typed
incarnation failures durably block the affected workspace's content traffic.

Protocol capability is remembered by canonical endpoint and authenticated user,
independently of workspace replacement and sign-out. Valid issuance/discovery
and strictly validated typed error metadata establish that capability when the
account identity is known; an email-only failed login cannot establish a user
identity. Invalid or contradictory metadata is never capability evidence.
Legacy fallback requires
an exact discovery-route 404 with no protocol evidence and either an empty body
or strict JSON `{"error":"not_found"}`, followed by strict successful `/me`
using the same credential and matching user. The capability is checked again
after that request. Proxy HTML, redirects, a different user, and other failures
never qualify. Once protocol 1 is known, a later 404 or missing metadata cannot
downgrade the authority. A legacy proof applies only to the exact credential;
it does not authorize a new incarnation.

## Durable local reset state

SQLDelight schema 2 adds the incarnation to the existing published-workspace
binding and stores installation-owned reset intents, workspace network gates,
and protocol capability. Migration from schema 1 backfills G0 without changing
existing content or media publication proof. The shared JVM driver lifecycle
rejects a schema newer than the running client before application writes. This
guard cannot change the behavior of already released older binaries; rollback
acceptance remains part of rollout validation.

Before reset POST, the shared use case rechecks the explicitly confirmed account,
workspace, writer and incarnation under the workspace lifecycle/product boundary,
then persists the operation ID and uncertainty state. No password, token or
request body enters that state. Network waiting releases the lifecycle boundary;
duplicate in-flight submission is rejected. The password-bearing POST is never
automatically refreshed or replayed.

A committed receipt records remote completion without deleting the local copy.
A missing receipt, timeout or lost response keeps the original operation and
network gate. A later definitive rejection cannot erase uncertainty from an
earlier submission. Only a first definitive rejection with verified unchanged
incarnation can clear that submission's intent. A local receipt-write failure
reports remote completion with local reconciliation still pending.

The explicit offline exit retains the intent and network block, permits local
editing and export, and invalidates earlier discard consent, including a
confirmation still waiting for discovery. Replacement of a copy carrying a
local reset intent requires a committed receipt, fresh consent for that exact
original copy, and current remote incarnation verification. A mismatch-only gate
from another device's reset needs the same fresh target verification and consent,
without inventing a local operation or requiring a receipt for it. Pair/Recover use the existing
staged-key and atomic workspace-replacement transaction; they reconcile the
intent in that same transaction. An unresolved outcome cannot authorize local
replacement. Incarnation capture is local metadata; existing pairing/recovery
envelopes and cryptographic vectors are unchanged.

Schema 3 adds a nullable target incarnation to the workspace gate for rejoin
consent after another device resets the account. Migration preserves every
existing row and sets the new field to null; an old gate never implies consent.
Rejoin freezes the original copy and records consent before device registration.
Registration alone cannot clear the gate or publish old data.

## Product workflow and workspace replacement

Android, iOS and Desktop expose the same shared account-data workflow in
settings. Its confirmation shows the endpoint/account and explains the scope
(all workspaces and devices), logical retirement, retained offline copies and
backups, image-free export, no undo/cooling period, and possible indefinite
blocking of another reset. Password and exact confirmation phrase are required.
Arbitrary server/exception text is never rendered as a reset error.

A successful remote reset leaves the local workspace intact. The password may
be reused only for the immediate ordinary login within that call. Control-only
credentials stay in memory; checking receipts or learning a new incarnation
never registers the writer or enables old content traffic. Restart or dismissal
drops memory credentials and confirmation reviews, preserving durable intent.
When credentials or connection hints are missing, the blocked-copy UI offers
control login with an editable email hint. Login must return the captured account
ID; it neither registers a device nor enables content access. Authentication
precedes local replacement confirmation. Reset availability affects only a new
reset, not reconciliation or rejoining after a committed reset.

The local decision is separate: export/keep editing offline, or explicitly
discard all local data and create a fresh workspace, pair, or recover. Export
requires the old key to be available; remote reset still works when it is not.
Uncertain outcomes allow an offline exit while retaining the original operation
and blocking content traffic. That exit remains available during control HTTP
and invalidates any earlier local replacement decision.

Fresh replacement generates a new workspace ID and master key while keeping the
installation writer. All three replacement modes use the existing key-staging
and SQLite transaction, including every old DAG generation, projection, protocol
row, media record, new authority, and intent/gate reconciliation. Pre-commit
failure preserves the old copy; unreferenced aliases/files are cleaned after
commit. The platform service graph is retained. Confirmed re-enrollment updates
the secure session and its settings while the old copy remains gated. After
replacement, clients reconcile session/recovery state and attempt initial sync;
a later network failure offers sync retry without repeating local replacement.
The first device publishes its new workspace before creating a new recovery
code; other devices join using that workspace's new invitation or recovery code.

Product mutations, including notes, media import, local/Day One import and
synced preferences, check the persistent gate inside the shared short product
boundary. Offline editing is allowed only after the explicit offline decision;
network publication stays blocked. A pending intent with a missing gate is
repaired conservatively as read-only. Current credentials from another
incarnation persist a mismatch gate before a product write can proceed.

Editors, image/document pickers and asynchronous reads capture workspace,
incarnation, authority and writer identity. Results are discarded if that copy
has been replaced; no completion can retag an old edit for the new workspace.
The UI freshness check uses only an in-memory snapshot. Identity/gate reads and
mutations run off the main thread; constructors and Compose builders do no IO.
The first authority binding of the same workspace/incarnation/writer preserves
an unsaved draft. A replacement invalidates editor/detail/media/conflict state.
Release acceptance and operator enablement remain Stage E; reset defaults off.

## Control reads

Both routes require bearer `auth` credentials for a current session, accept no
target account or admin cookie, and send `Cache-Control: no-store`, including
errors. No device-bound workspace credentials are required.

`GET /account/data-state` returns exactly:

```json
{
  "protocolVersion": 1,
  "accountIncarnation": "00000000-0000-0000-0000-000000000000",
  "resetAvailable": false,
  "resetUnavailableReason": "deployment_not_ready"
}
```

The incarnation reflects the authenticated account. Availability is advisory:
readiness failure reports `deployment_not_ready`; otherwise unreclaimed older
media reports `retired_media_pending`. When neither applies, availability is
true and the reason is null. POST rechecks all preconditions. A failed runtime
probe can recover on a later discovery or reset attempt, outside DB admission. `GET /account/data-resets/{operationId}` accepts a canonical
UUIDv4. A matching account-owned committed receipt returns `protocolVersion`,
`operationId`, `previousIncarnation`, `newIncarnation` and
`committedAtEpochMillis`; absence returns `404 not_found`. There is no list,
cancel or pending-receipt route.


## Atomic reset

`POST /account/data-resets` requires bearer `auth`, `no-store` responses and a
strict JSON body of at most 4 KiB:

```json
{
  "protocolVersion": 1,
  "operationId": "11111111-1111-4111-8111-111111111111",
  "expectedIncarnation": "00000000-0000-0000-0000-000000000000",
  "confirmation": "reset-all-account-data-v1",
  "password": "<entered for this operation>"
}
```

Both protocol headers are required; the header incarnation must match the body.
The operation ID is a canonical UUIDv4. Duplicate/unknown fields, invalid UTF-8,
noncanonical identities and wrong confirmation are rejected. Password-bearing
objects are redacted and never persisted. Requests use bounded client/account
rate limits and existing bounded Argon2 work.

Under short shared admission, require current authentication and inspect the
account-owned receipt. Exact `(user, operationId, protocolVersion,
expectedIncarnation)` replay returns the stored receipt without password work,
even if reset is now disabled or the expected incarnation is old. Different
identity conflicts. A stale session still requires fresh ordinary login.

For a new operation, verify the password outside account admission, then check
required storage readiness outside any DB connection. Inside one exclusive
`READ COMMITTED` transaction, recheck current authority and any receipt, the
verified password-hash snapshot, expected incarnation, enablement/readiness,
unreclaimed earlier media and at most three commits per rolling 24 hours.
Retire the current incarnation, create a random successor, delete the
account-current recovery envelope, and insert the receipt atomically. Sample
retirement using database wall-clock time after acquiring the exclusive lock.
Return 200 only after COMMIT. Old sessions, including the initiator, become stale;
old invitations and workspaces lose access by incarnation. Device claims,
password, administrator role, old content and blobs are retained at this step.

A confirmed rollback leaves no partial transition. A lost/uncertain COMMIT
response does not prove failure: retain the operation ID, log in again and read
its receipt. Receipt absence permits retry within current database history;
it cannot prove that a reset was absent before a backup rollback. No accepted,
pending, background reset or reset-specific credential is created.

## Reclamation and restore

Any retired incarnation with a null `media_reclaimed_at` blocks another new
reset. Quota release is logical; it does not establish physical deletion.
`purge-retired-account-data` is the operator-only owner of bounded SQL cleanup,
blob deletion and certification. The HTTP media adapter has no list/delete API.
Workspace tombstones, incarnation history, receipts and global device claims
remain. Scrubbing a retired device cannot alter a newer enrollment.

Certification requires a subsequent complete empty scan of the exact retired
namespace, including orphan files, temporary files, current objects, versions
and delete markers. Filesystem publication must have drained synchronously.
For S3, the final scan must begin at least 24 hours after retirement, and the
operator must attest that pre-retirement writes have settled and enumeration
is complete and consistent. This is an operational assumption, not an S3
finality guarantee. Retention, permission errors, partial scans or interruption
before certification leave the marker empty. A lost certification COMMIT response
has an unconfirmed outcome and requires fresh inspection. Empty filesystem
directory scaffolding may remain after all stored files are gone. Indefinite
retention can prevent later resets forever.

V11 binds maintenance to the database verification context and a fresh audit
ID. Execution clears a prior marker before inspection, retaining knowledge of
prior certification across interruption. A newer audit or restored context
invalidates an older result. Finding objects after prior certification records
a persistent assumption violation, leaves the marker cleared, and requires
reset disablement and explicit operator revalidation. Storage configuration
must also match the identity captured by the dry run.

Restore must stop ingress and maintenance, discard old deletion plans, rotate
the verification context and invalidate retired-media attestations before
reopening. A historical backup may restore old incarnations, content, sessions,
password/revocation state and receipts; there is no external reset journal or
client rollback detector in this version. See the
[maintenance guide](../guides/account-data-reset-maintenance.md) for credentials,
attestations, command usage and restore ordering.
