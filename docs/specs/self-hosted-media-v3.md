# Self-Hosted Media V3

Status: implemented bounded-image wire, append-only storage, and
filesystem/S3-compatible server backends.

## Wire format

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

Deterministic encryption makes a retry of the same immutable local original
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
reported unavailable. The same authenticated client can reconstruct a missing
blob with an exact PUT replay. A divergent existing blob is never overwritten;
it requires operator recovery from a valid media-store copy.

There is no list, patch, chunk, manifest, draft, finalize, or delete endpoint.

## Storage and quota

PostgreSQL stores bounded metadata and the configured blob store holds the
ciphertext. The server supports two blob backends: filesystem for standalone
deployments and S3-compatible object storage for external deployments.

A filesystem implementation may shard that logical identity internally. The
current layout is:

```text
<root>/<user-id>/<workspace-id>/<media-id[0:2]>/<media-id[2:4]>/<media-id>/object.bin
```

This physical layout is not part of the wire contract.

The S3 adapter uses the same identity beneath a fixed `media/v1/`
prefix. It requires private PUT/HEAD/GET, strong read-after-write behavior, and
conditional create equivalent to `If-None-Match: *`. The server does not use
public object URLs, expose storage credentials, rely on ETag as SHA-256, invoke
bucket listing, or require multipart upload. The provider must keep missing
HEAD/GET responses distinguishable from permission denial. After conditional
create reports an existing object, a bounded GET and SHA-256 of its actual
bytes are required before it can be accepted as an exact replay; object
metadata alone is not authoritative.

Blob publication precedes the PostgreSQL metadata commit. A transaction
failure may leave an invisible immutable orphan; an exact retry reuses that
object, while different bytes at the same identity are always rejected. The
current server leaves these orphans in place and does not delete objects at
runtime.

Quota is checked atomically per account across all workspaces and counts
PostgreSQL-indexed published ciphertext bytes. A safe orphan consumes backend
capacity without counting against published account quota. Published objects
are not currently garbage-collected.

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

Supported content is static JPEG, PNG, and WebP. SVG, animation, video, general
attachments, and automatic URL fetching are unsupported.

Portable JSON export and restore do not contain media bytes.
Self-hosted operators must protect PostgreSQL and the configured blob store as
one logical recovery unit. Standalone uses coordinated database and off-host
directory backups. The recommended external topology uses PostgreSQL recovery
points plus bucket versioning/retention. A valid recovery set must contain an
exact key, length, and actual-byte SHA-256 match for every PostgreSQL media
record; extra unreferenced objects are harmless. See
`server-backup-and-recovery.md`.
