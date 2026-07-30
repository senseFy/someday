# WebDAV System V2 Profile

Someday uses WebDAV as a weak object store for the **System V2** sync contract.

Authoritative protocol: [`docs/sync-system-v2-spec.md`](sync-system-v2-spec.md).

## Product split

1. **Incremental sync** — encrypted workspace-entity objects under the app directory `log-v2/` tree (epoch pointer, checkpoints, writer logs, repairs).
2. **Disaster recovery backup** — separate explicit backup/restore actions; not the sync log.

## Assumed WebDAV capabilities

- `MKCOL`, `PROPFIND Depth: 1`, `GET`, `PUT`
- `If-None-Match: *` for append-only creates
- `If-Match: <etag>` for conditional updates

No dependence on `LOCK`, `Depth: infinity`, RFC 6578 sync tokens, or `PATCH`.

## Workspace pairing

Devices that share a WebDAV app directory must share the same workspace key.
Pairing uses a 128-bit random capability transferred by QR or a checksummed
28-character manual token. The workspace package is encrypted end to end and
stored at:

```text
workspace-pairing/1/<invitation-id>.json.enc
```

Invitation creation is append-only with `If-None-Match: *`. Claim and
cancellation replace the exact record with an authenticated tombstone using
the ETag from `GET` and `If-Match`. The pairing path never sends `DELETE`;
expired envelopes and terminal tombstones remain unavailable to replay.

The capability is valid for no more than ten minutes and is bound to the
normalized WebDAV endpoint, username, and app directory. See
[`workspace-pairing-protocol.md`](workspace-pairing-protocol.md) for token
encoding, key derivation, envelope AAD, state transitions, and threat model.

## Reset

Remote tree reset is unavailable until it can publish a complete checkpoint
from the current DAG and atomically install a successor epoch. Current code
never recursively deletes `log-v2/`.

## First-epoch publish (implementation note)

First-epoch publication still requires a complete local product checkpoint:
immutable chunks, then the manifest, then remote read-back verification, then a
single CAS on the epoch pointer. Clients may upload independent checkpoint
chunks concurrently (bounded parallelism; default 4) because each chunk path is
append-only and content-addressed. Manifest, verify, and pointer steps remain
ordered after all chunks succeed.

`PREPARING` is only created inside the authority-mutation coordinator during an
explicit Sync/activation attempt (including crash-resume of an interrupted
publish). Bulk local import does **not** open a long-lived preparing epoch:
that would flip the device into key-bound V2 state and block backup, re-import,
and pairing until sync completes.

Genesis inventory fingerprint checks apply only to first-epoch drafts built
from `local-product:*` sources (`previousEpochId` and `previousPointerDigest`
both null). Rollover and recovery drafts use V2 head digests and must not be
validated with the genesis inventory path; they re-check their exact source-head
identity set under the commit barrier instead. When a draft is stale before
CAS, the client marks the never-authoritative local graph abandoned and rebuilds
from current state on the appropriate retry path. The exact control identities
stay locally retained until authenticated remote cleanup completes. Transient
empty-remote CAS failures keep the original PREPARING identity and end the run
so the next Sync resumes the same durable checkpoint. Typed upload progress is
formatted by the UI layer.

Product routing and checkpoint publication share a short commit barrier. The
barrier covers the final local snapshot check, pointer CAS, and local authority
activation. Adopting an already-published authenticated checkpoint uses the
same barrier and activation transaction to preserve local fallback state (or
unpublished prior-epoch writes) before the route switches. Checkpoint
construction, encryption, upload, download, and read-back remain outside it. A
product operation that reaches the barrier waits, then re-evaluates routing
against the newly activated authority. This closes the pre-CAS mutation window
without freezing editing for the full upload.

Never-authoritative checkpoint objects remain locally identifiable until
remote cleanup succeeds. Cleanup runs only after the current pointer has been
authenticated and the draft's expected predecessor is no longer current.
Self-hosted cleanup checks references and deletes under the workspace lock;
WebDAV cleanup authenticates current/history pointers and uses exact,
ETag-conditional object deletes. Missing authentication, a still-publishable
draft, an immutable mismatch, or a transient transport failure retains the
cleanup record for a later sync.
