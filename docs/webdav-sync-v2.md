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
