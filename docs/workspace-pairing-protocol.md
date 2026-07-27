# Workspace Pairing Protocol

Status: implementation and interoperability contract.

This protocol gives one additional device a workspace master key without
revealing that key to WebDAV or the self-hosted service. The user transfers one
high-entropy capability by QR code or manual entry. The capability is
single-use, expires after at most ten minutes, and is never written to logs.

There is one pairing protocol. Implementations must not accept a shorter
numeric token, probe an alternate remote path, or derive lookup identifiers
with a fast enumerable hash.

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
cannot prevent denial of service by a storage provider, server operator, or
another party with write access to the same WebDAV directory.

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

Pairing is bound to the configured remote authority. Each component is encoded
as `<UTF-8 byte length>:<value>`, then components are joined with `|`.

- WebDAV components: normalized endpoint, username, normalized app directory.
- Self-hosted components: endpoint with trailing slash removed, authenticated
  user id.

The authority binding is authenticated but is not included in encrypted
plaintext.

## 5. Encrypted envelope

The exact outer JSON field set is:

```json
{
  "format": "someday.workspace-pairing",
  "protocolVersion": 1,
  "remoteProfile": "webdav",
  "inviteId": "<22-character id>",
  "createdAtEpochMillis": 1000,
  "expiresAtEpochMillis": 601000,
  "cipherSuite": "xchacha20-poly1305-ietf",
  "keyDerivation": "hkdf-sha256",
  "nonce": "<unpadded Base64url>",
  "ciphertext": "<unpadded Base64url>"
}
```

`remoteProfile` is either `webdav` or `self-hosted`. The nonce is 24 random
bytes. Encrypt with XChaCha20-Poly1305 using the envelope key.

Associated data is these values joined by `\n`, without a final newline:

```text
someday.workspace-pairing
1
<remote profile>
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

## 6. WebDAV state machine

The record path is:

```text
workspace-pairing/1/<invitation-id>.json.enc
```

Creation uses `PUT` with `If-None-Match: *`. A collision generates a new
capability, up to three attempts.

Claim performs:

1. reject any key-bound local System V2 state;
2. acquire the shared workspace-authority mutation lock and check local state
   again;
3. `GET` the record and retain its exact ETag;
4. validate expiry, authority, digest, and authenticated envelope;
5. create a random 16-byte claim id;
6. replace the envelope with an authenticated `claimed` tombstone using the
   exact `If-Match` ETag;
7. commit the workspace adoption only after the conditional update succeeds.

Cancellation replaces an available envelope with an authenticated `cancelled`
tombstone using the same conditional update. A lost update response is
resolved by reading and comparing the complete authenticated tombstone.

The tombstone fields are format, protocol version, invitation id, state,
optional claim id, envelope digest, expiry, and authenticator. The
authenticator is HMAC-SHA-256 under the state key over those fields plus the
authority binding. Claimed tombstones require a claim id; cancelled
tombstones forbid one.

Pairing never sends WebDAV `DELETE`. Claimed, cancelled, and expired records
remain as replay tombstones or unavailable envelopes. Retention collection is
not part of this protocol revision.

## 7. Self-hosted state machine

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
claim even when validation or local adoption fails, preserving single-use
behavior. Pairing uses the same serialized access-token refresh executor as
normal self-hosted sync.

## 8. Local workspace adoption

Joining is forbidden when any local key-bound System V2 lifecycle exists:
`preparing`, `active`, `blocked`, or `read_only`. The check happens before
remote claim and again while holding the shared authority mutation lock.
First-epoch activation uses that same lock, so activation and workspace
adoption cannot overlap.

The imported recovery package must authenticate the workspace metadata,
workspace id, and expected key fingerprint. The new key is first written
under a fresh secure-storage alias. Local metadata is then committed. If that
commit fails, the staged alias is removed and the prior workspace remains
active. The prior alias is removed only after the new metadata commit.

There is no DAG-rebinding transaction. A device with key-bound history must
clear its local app data before joining a different workspace.

## 9. UI and release rules

- Android and iOS request camera access only after the user selects Scan.
- Android accepts QR metadata through CameraX and ZXing; iOS accepts QR
  metadata through AVFoundation. Desktop supports manual entry.
- Invitation objects, derived key objects, feedback, exceptions, and
  capability logs redact secret text.
- The UI removes an invitation at its exclusive expiry and allows creation of
  a replacement.
- English, Simplified Chinese, Japanese, and Korean use the same pairing
  semantics.

Workspace pairing does not override the sync activation release gate. Both
activation properties remain off by default. The accepted complete System V2
gate permits canonical shipping entrypoints to set release activation on while
forcing development activation off; ad-hoc low-level builds do not inherit
that permission.

## 10. Required evidence

The reliability gate must cover:

- token normalization, checksum, golden derivation vector, strict envelope
  decoding, authority binding, and expiry;
- WebDAV append-only create, conditional one-use claim, authenticated
  cancellation, missing-ETag failure, and absence of delete;
- self-hosted account/device scoping, atomic claim, replay behavior,
  completion, cancellation, expiry, and absence of read/delete routes;
- local key-bound-state refusal and authority-mutation serialization;
- encrypted end-to-end join followed by follower bootstrap and Notes refresh;
- Android, iOS, Desktop, shared UI, server, and real-remote builds/tests.
