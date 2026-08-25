# Self-Hosted Media V3

Status: wire and storage contract for bounded image synchronization.

## Contract

Media V3 stores one immutable encrypted object per image at:

```text
PUT  /sync/v3/workspaces/{workspaceId}/media/{mediaId}
HEAD /sync/v3/workspaces/{workspaceId}/media/{mediaId}
GET  /sync/v3/workspaces/{workspaceId}/media/{mediaId}
```

The capability document advertises:

- subsystem: `someday-system-v3-media-v1`;
- schema: `1`;
- cipher suite: `xchacha20-poly1305-ietf`;
- mode: `deterministic-single-object-v1`;
- maximum plaintext: 4,194,304 bytes;
- maximum ciphertext: 4,198,444 bytes;
- immutable PUT and HEAD support.

`workspaceId` is `workspace-` plus 32 lowercase hex characters. `mediaId` is
64 lowercase hex characters. Both are path identities; neither is accepted
from a duplicate JSON body field.

## Plaintext envelope and encryption

Before encryption the client constructs:

```text
4-byte big-endian metadata length
strict UTF-8 JSON metadata (at most 4 KiB)
original image bytes (at most 4 MiB)
```

Metadata authenticates schema and contract IDs, media ID, canonical media
type, optional safe basename, positive dimensions, plaintext byte count, and
SHA-256. Pixel width multiplied by height may not exceed 12,000,000.

The media key is derived from the workspace object subkey with a domain,
`workspaceId`, and `mediaId`. The deterministic nonce additionally binds the
complete plaintext-envelope digest. AAD binds its own domain, `workspaceId`,
`mediaId`, and envelope length. The transmitted bytes are the 24-byte nonce
followed by XChaCha20-Poly1305 ciphertext and tag.

Determinism exists only to make retry of the same immutable local original
byte-identical. The random workspace-scoped media ID prevents public
cross-asset content equality disclosure.

## HTTP behavior

All routes require an authenticated, non-revoked device session with `sync`
scope. The server scopes storage by `(userId, workspaceId, mediaId)`.

PUT requires content type `application/vnd.someday.media-object.v1` and the
canonical ciphertext SHA-256 header. It rejects malformed IDs, bodies outside
the ciphertext bound, a header/body digest mismatch, quota exhaustion, or a
different value already stored at that identity. Exact replay returns success.

HEAD and GET return ciphertext length and digest headers. GET returns only
bytes whose blob metadata matches PostgreSQL. A missing or divergent blob is
reported unavailable; the same authenticated client can reconstruct it with
an exact PUT replay.

There is no list, patch, chunk, manifest, draft, finalize, or delete endpoint.

## Storage and quota

PostgreSQL stores bounded metadata and the configured blob store holds the
ciphertext. A filesystem implementation uses an account/workspace-sharded key:

```text
<root>/<user-id>/<workspace-id>/<media-id-prefix>/<media-id>.bin
```

Quota is checked atomically per account across all workspaces. It counts
stored ciphertext bytes only; there are no draft reservations. Published
objects are not garbage-collected in the initial release.

## Client publication proof

Local media metadata records proof as this tuple:

```text
remote authority binding + workspaceId + ciphertext digest
```

The proof is valid only for that exact account and workspace. Before an entity
batch or checkpoint is published, every referenced media ID must have a
verified remote object or a verified local original that can be uploaded.

Downloads authenticate and decrypt the envelope, compare every declared
identity field, validate the original image format and bounds, and only then
atomically promote bytes into app-private storage.

## Supported content and backup

Only static JPEG, PNG, and WebP are accepted. SVG, animation, video, general
attachments, and automatic URL fetching are unsupported.

Portable JSON export/restore does not contain media bytes in this release.
Self-hosted operators must back up PostgreSQL and the blob directory as one
logical unit.
