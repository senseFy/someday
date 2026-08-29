# Workspace Pairing Protocol

Status: implemented interoperability specification.

This protocol gives one additional device a workspace master key without
revealing that key to the self-hosted service. The user transfers one
high-entropy capability by QR code or manual entry. The capability is
single-use, expires after at most ten minutes, and is never written to logs.

Implementations accept the complete token format below. Short numeric tokens,
alternate remote paths, and lookup identifiers derived with a fast enumerable
hash are invalid.

## 1. Security boundary

The protocol is designed for these conditions:

- a database reader, storage provider, or self-hosted operator can read every
  remote pairing record but does not know the transferred capability;
- the remote may reorder, replay, replace, or withhold bytes;
- another device using the same remote credentials may race a legitimate
  claimant;
- TLS protects transport credentials and server sessions in transit.

Remote storage learns an opaque invitation identifier, timestamps, state, and
ciphertext size. It cannot recover the workspace key from those values. A
party that obtains the QR payload or manual token has the complete invitation
capability and can claim it during its lifetime. The checksum detects typing
errors; it is not an authentication factor.

The protocol protects confidentiality and authenticated state transitions. It
cannot prevent denial of service by the server operator or another authenticated
device on the same account.

## 2. Capability encoding

Generate exactly 16 random bytes with the platform cryptographic random
source. Encode those bytes as 26 canonical Crockford Base32 characters. Append
two checksum characters, producing a 28-character canonical token.

The checksum input is:

```text
UTF8("someday.workspace-pairing.token-checksum.1\0") || secret
```

Compute SHA-256, Crockford-Base32 encode the digest, and use its first two
characters. The UI groups the canonical token as four groups of seven
characters separated by spaces.

Manual parsing:

- accepts only ASCII input;
- accepts an optional case-insensitive `SOMEDAY:PAIR:1:` prefix;
- removes ASCII spaces and hyphens;
- uppercases letters and applies the Crockford aliases `O → 0` and
  `I/L → 1`;
- requires exactly 28 characters, canonical unused trailing bits, and a valid
  checksum.

The QR payload is exactly:

```text
SOMEDAY:PAIR:1:<28-character canonical token>
```

Scanners return this text to the shared parser. They must not open it as a URL.

## 3. Key derivation

Use HKDF-SHA-256 with:

```text
salt = UTF8("someday.workspace-pairing.hkdf-sha256.1")
PRK  = HMAC-SHA256(salt, secret)
OKM  = HMAC-SHA256(PRK, UTF8(info) || 0x01)
```

The protocol derives:

| Purpose | `info` | Output |
| --- | --- | --- |
| Invitation identifier | `someday.workspace-pairing.invite-id.1` | first 16 bytes, unpadded Base64url |
| Envelope key | `someday.workspace-pairing.envelope-key.1` | 32 bytes |
| State authentication key | `someday.workspace-pairing.state-key.1` | 32 bytes |

The 16-byte invitation identifier is always 22 canonical unpadded Base64url
characters.

Golden vector for secret bytes `00 01 02 … 0f`:

```text
token       = 000G40R40M30E209185GR38E1WRJ
invite id   = f4Yo0a1Q85vFPJvFGsQYwg
envelope key= d946242a3d6f6eb43bcaa5fa8d7046910deeed24e32519f8dfdf5b59515db0bf
state key   = 78150e109eb945bbbdbea114fa20c7cdaaf19c4c045d8ac2b8b7a318e9926eec
```

Any change to this vector is a protocol change.

## 4. Authority binding

Pairing is bound to the configured self-hosted remote account. Each component
is encoded as `<UTF-8 byte length>:<value>`, then components are joined with
`|`.

- Self-hosted components: canonical endpoint (lowercase scheme/host, default
  port and trailing slash removed), authenticated user id.

The authority binding is authenticated but is not included in encrypted
plaintext.

The envelope uses endpoint plus account because those values must be identical
on the inviting and joining devices. Separately, each published local
workspace has a publication-session binding:

```text
canonical endpoint + authenticated userId + workspaceId + local writer deviceId
```

The shared session guard enforces all four values before an already published
workspace creates or cancels pairing state. An inviter must have an `ACTIVE`
published pointer; a merely local `PREPARING` draft is not an invitation
authority. A wrong account, workspace, or device fails before join-package
creation and before an invite request.

A joining installation claims with its own registered device session. Successful
local replacement binds that stable installation device id as the new DAG writer.
The writer id and workspace id are not part of envelope AAD because the
inviter and joiner have different device ids, and the authenticated encrypted
payload already carries the exact workspace id being joined.

## 5. Encrypted envelope

The exact outer JSON field set is:

```json
{
  "format": "someday.workspace-pairing",
  "protocolVersion": 1,
  "inviteId": "<22-character id>",
  "createdAtEpochMillis": 1000,
  "expiresAtEpochMillis": 601000,
  "cipherSuite": "xchacha20-poly1305-ietf",
  "keyDerivation": "hkdf-sha256",
  "nonce": "<unpadded Base64url>",
  "ciphertext": "<unpadded Base64url>"
}
```

The nonce is 24 random bytes. Encrypt with XChaCha20-Poly1305 using the
envelope key.

Associated data is these values joined by `\n`, without a final newline:

```text
someday.workspace-pairing
1
<invitation identifier>
<created milliseconds>
<expiry milliseconds>
xchacha20-poly1305-ietf
hkdf-sha256
<authority binding>
```

The encrypted payload has exactly four string fields:

```json
{
  "metadataJson": "<workspace recovery metadata>",
  "recoveryCode": "<workspace recovery material>",
  "workspaceId": "<workspace id>",
  "keyFingerprint": "<expected key fingerprint>"
}
```

Decoders require valid UTF-8, strict JSON, the exact field sets and types,
unique object keys, canonical unpadded Base64url, the expected identifiers,
and authenticated decryption. The payload is limited to 47 KiB and the outer
envelope to 64 KiB.

An invitation lifetime must be positive and no longer than 600,000
milliseconds. `expiresAtEpochMillis` is exclusive: an invitation is expired
when `now >= expiresAtEpochMillis`.

The envelope digest is unpadded Base64url SHA-256 of the exact outer JSON
bytes.

## 6. Self-hosted state machine

All routes require an authenticated, non-revoked device session with `sync`
scope. Records are keyed by `(user id, invitation id)`, so an invitation
cannot cross accounts.

```text
PUT  /pairing/invites/<id>
POST /pairing/invites/<id>/claim
POST /pairing/invites/<id>/complete
POST /pairing/invites/<id>/cancel
```

There is no read or delete route.

- Create stores the opaque envelope and digest. Exact replay by the same
  creator device is idempotent; different bytes conflict. The server caps
  expiry at ten minutes and permits at most eight active invitations per
  account.
- Claim atomically changes `available → claimed`. Repeating the same claim id
  from the same device returns the same envelope. Any competing claim
  conflicts.
- Complete requires the claiming device and claim id, changes
  `claimed → completed`, and clears envelope ciphertext. Exact replay is
  idempotent.
- Cancel requires the creator device, changes `available → cancelled`, and
  clears envelope ciphertext. Exact replay is idempotent.
- Expired records return Gone and may be collected.

The client checks the server digest, expiry, authority-bound envelope, and
workspace package itself. Completion is attempted after every successful
claim even when validation or local replacement fails, preserving single-use
behavior. Pairing uses the same serialized access-token refresh executor as
normal self-hosted sync. Creation requires the inviter's active publication
binding. A fresh installation may claim before it has a local publication
binding.

## 7. Local workspace replacement

Every installation already has a local workspace. Joining another workspace
therefore requires explicit, per-attempt user confirmation that the current
local workspace will be discarded without merging. Without that confirmation,
the client refuses before remote claim. Confirmation may authorize replacement
of an `ACTIVE`, bound, `BLOCKED`, unhealthy, ambiguous, or contentful workspace;
none of those states grants implicit consent.

Replacement is local to the joining installation. It does not delete the old
workspace or its media from the server, and other devices may continue using
that workspace. There is no DAG merge or workspace rebinding operation.

The imported recovery package must authenticate the workspace metadata,
workspace id, and expected key fingerprint before local state changes. The new
key is first written under a fresh secure-storage alias. While holding the
shared workspace-lifecycle coordination boundary, one database transaction
then removes every old local generation and its DAG, projections, protocol
state, and media records, installs the joined workspace metadata, and binds its
new authority. Device identity, authenticated session, and installation-local
preferences remain intact.

If validation or secure-key staging fails, no local database state changes. If
cleanup, metadata installation, or authority binding fails, the transaction
rolls back, the staged alias is removed, and the prior workspace remains
usable. The prior alias is removed only after commit. A normal sync or product
mutation cannot write old-workspace state after replacement commits.

Media files are outside the database transaction. After commit, the client
best-effort removes files no longer referenced by the new database state. A
filesystem cleanup failure may leave harmless orphan files for later cleanup;
it does not turn the committed replacement into a pairing failure. Likewise,
failure of the first sync after replacement is retryable and does not restore the
discarded workspace.

## 8. UI and release rules

- Android and iOS request camera access only after the user selects Scan.
- Android accepts QR metadata through CameraX and ZXing; iOS accepts QR
  metadata through AVFoundation. Desktop supports manual entry.
- Invitation objects, derived key objects, feedback, exceptions, and
  capability logs redact secret text.
- The UI removes an invitation at its exclusive expiry and allows creation of
  a replacement.
- English, Simplified Chinese, Japanese, and Korean use the same pairing
  semantics.

The local DAG is the product data model from workspace creation onward,
including while network sync is off. Pairing requires a valid self-hosted
session and, for the inviter, an active published workspace.

## 9. Required evidence

The reliability gate must cover:

- token normalization, checksum, golden derivation vector, strict envelope
  decoding, authority binding, and expiry;
- self-hosted account/device scoping, atomic claim, replay behavior,
  completion, cancellation, expiry, and absence of read/delete routes;
- refusal before claim without explicit replacement confirmation;
- confirmed replacement of active, bound, and contentful local workspaces;
- atomic rollback that preserves all prior local state when validation,
  cleanup, metadata installation, or authority binding fails;
- complete transactional removal of old DAG, projection, protocol, and media
  records, plus post-commit best-effort media-file cleanup;
- serialization with active sync, local product mutation, and initial
  authority establishment so old-workspace state cannot reappear;
- inviter rejection before active publication and stable UUIDv4 writer
  binding on the joining device;
- encrypted end-to-end join followed by follower bootstrap and Notes refresh;
- Android, iOS, Desktop, shared UI, server, and real-remote builds/tests.
