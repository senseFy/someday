# Account reset operations and retired-data maintenance

This guide covers the implemented server reset and maintenance boundary.
[The protocol](../specs/account-data-reset-protocol.md) owns admission, API,
retention and certification semantics. Client reset/rejoin UI is implemented;
coordinated rollout and deployment-specific storage certification remain
pending. Keep account reset disabled until that rollout is ready.

## Enabling logical reset

`SOMEDAY_ACCOUNT_RESET_ENABLED` defaults to `false`; only exact `true` and
`false` are accepted. Deployment examples default off. Before enabling it:

- Drain every server process predating incarnation admission and complete the
  compatible client rollout.
- Verify coordinated PostgreSQL/media backup and restore, and explain that
  reset preserves account/password but removes access to every old workspace.
- Verify active-object protection for legacy and `.incarnations/v1/` paths,
  including explicit policy/lifecycle exceptions. Runtime probes do not prove
  retention policy, deletion denial or remote-write settlement.
- Record whether this is a logical-only retention profile or a separately
  certified reclamation profile. Retention may prevent a second reset forever;
  do not weaken the recommended R2 bucket lock to enable cleanup.

Setting the flag to true declares that this review has happened. Startup checks
both physical layouts before serving; an existing nonzero incarnation keeps
the nested check mandatory even after the flag is disabled. New POST requests
recheck storage readiness without holding a DB connection. Disablement preserves
receipts and existing incarnation fences.

## Separate maintenance identity

The HTTP process retains PUT/HEAD/GET-only media capabilities. The maintenance
executables use the deployment's database URL/TLS and media backend settings,
but require `SOMEDAY_MAINTENANCE_DB_USER` and
`SOMEDAY_MAINTENANCE_DB_PASSWORD`; they do not fall back to application database
credentials. Provision a distinct non-superuser, non-BYPASSRLS role. Grant:

- schema `USAGE` and `SELECT` on `someday_maintenance_context`,
  `someday_account_data_incarnations`, `someday_entity_workspaces`,
  `someday_devices`, `someday_sessions`, `someday_refresh_tokens`,
  `workspace_pairing_invites`, `workspace_recovery_envelopes`,
  `someday_media_v3_objects`, and the entity tables listed below;
- `DELETE` on `someday_sync_v2_changes`, `someday_sync_v2_mutations`,
  `someday_sync_v2_objects`, `someday_sync_v2_checkpoint_chunks`,
  `someday_sync_v2_checkpoint_manifests`, `someday_sync_v2_epochs`,
  `someday_media_v3_objects`, `workspace_pairing_invites`,
  `workspace_recovery_envelopes`, `someday_refresh_tokens` and `someday_sessions`;
- `UPDATE` on incarnation columns `media_audit_id`, `media_reclaimed_at`,
  `media_last_certified_at`, `media_revalidation_required`, the maintenance
  `verification_context`, and device `name`, `platform`, `last_seen_at`.

It needs no user deletion, device deletion, schema mutation, role-management or
retention-policy permission. Existing forced RLS still applies; the command sets
an exact account and transaction-local workspace wildcard for each SQL batch.
An invalidation-only identity needs just SELECT/UPDATE on the two maintenance
metadata tables, as exercised by the recovery gate.

For S3, supply a separate static identity through
`SOMEDAY_MAINTENANCE_S3_ACCESS_KEY_ID`,
`SOMEDAY_MAINTENANCE_S3_SECRET_ACCESS_KEY`, and optional
`SOMEDAY_MAINTENANCE_S3_SESSION_TOKEN`. Grant complete `ListBucket` and
`ListBucketVersions` for `media/v1/*`, plus `DeleteObject` and
`DeleteObjectVersion` for objects in that prefix. The production command does
not need PUT or governance-retention bypass. Gate fixtures additionally grant
PUT/GET only to seed synthetic versions and verify their survivors. The runtime
identity must retain its narrower policy.

Filesystem maintenance requires a supported descriptor-relative secure directory
stream, access to the exact existing configured root, and permission to remove
retired files. Use the canonical real path with no symlink ancestors. The
command rejects symlinks, path escapes and an absent configured root. It never
reclaims the startup-marker roots or a current-incarnation namespace.
Empty directory scaffolding may remain; filesystem certification means that
the complete retired tree contains no stored files, including temporary files.
The supported Linux container provides secure directory streams. A JVM/provider
without that capability, including the current macOS development JVM, rejects
filesystem maintenance without deleting files or recording completion.

## Inspect and execute

Use the installed launcher under `server/build/install/server/bin/`, or the
same named command through the container entrypoint. Keep maintenance credentials
out of persistent HTTP container configuration; inject them only into the
operator process.

Start with a read-only audit of one exact retired target:

```bash
purge-retired-account-data --user <account-uuid> --incarnation <retired-incarnation-uuid>
```

The command reports database identity, verification context, storage identity,
status and entry counts; it does not print content or secrets. Entry counts cover
listing observations, including versions and delete markers, rather than unique
logical media objects. Preserve these values only for the immediate operation.
The storage fingerprint binds the filesystem root/path identity or S3
endpoint/bucket/region/path-style settings; switching to an empty store cannot
reuse the previous dry run.
Storage enumeration uses bounded pages but must visit every page in the exact
retired root. There is no guaranteed total audit duration; interruption remains
incomplete evidence and a later run starts a fresh audit.

Stop/drain any old or uncertain publishers. Then execute using values from that
fresh audit:

```bash
purge-retired-account-data --user <account-uuid> --incarnation <retired-incarnation-uuid> \
  --database-identity '<database_identity>' \
  --verification-context <verification_context> \
  --storage-identity '<storage_identity>' \
  --execute --publishers-drained --profile-attested
```

For S3 add `--retirement-clock-trusted`. Its final certification scan cannot
start before 24 hours after database retirement. `--profile-attested` declares
complete consistent listing and that all old publications, including timed-out
PUTs, have settled by that deadline, with no external or suspended writer able
to resume. These are deployment assumptions; S3 does not guarantee this deadline.
Certify the intended provider and policy on disposable resources first.

Execution clears any prior completion marker before auditing. SQL deletion is
bounded and restartable, retaining workspace tombstones, device claims,
incarnation history and receipts. A deletion scan is never certification: a
later complete empty scan must succeed and still match database, audit and
storage context. SQL cleanup may finish even when retention prevents media
reclamation. Errors or interrupted work leave completion unconfirmed.

Exit `0` means a completed dry run or successful certification; inspect `status`
to distinguish them. Exit `2` is incomplete work such as settling, remaining
objects, SQL batch limit or an assumption violation. Exit `1` is invalid input,
changed context, denied access or another failure; a DB connection failure can
have an unknown commit outcome, so inspect again before acting. Some providers
report the same access-denied response for retention and IAM denial; the command
reports that ambiguity without changing policy.

Objects found after earlier certification create a persistent assumption
violation. Disable reset, investigate and revalidate the profile. A fresh audit
and explicit execution with `--operator-revalidated --reset-disabled` then permit
new certification; the configured reset flag must also be off. This declaration
cannot stop another running server by itself. Never blindly rerun an old plan.

## Restore invalidation

Keep ingress and all maintenance stopped throughout restoration and invalidation.
Do not resume a paused purge process. After restoring the coordinated recovery
unit with schema V11 or newer, inspect the restored database:

```bash
invalidate-reclamation-attestations
invalidate-reclamation-attestations --execute \
  --database-identity '<databaseIdentity from the preceding inspection>' \
  --ingress-stopped --maintenance-stopped
```

This rotates the verification context, clears all retired-media completion
markers and requires operator revalidation. If upgrading an older recovery point,
apply the forward migrations behind closed ingress before this step. Then run
media integrity and client read checks from the
[backup guide](server-backup-and-recovery.md). Each later purge requires a fresh
post-restore audit; a formerly retired namespace may now be active.

The supported restore gate exercises the invalidation launcher with a distinct
restricted maintenance role. It does not preserve reset across historical
rollback: old data, credentials and password/revocation state can return, and a
later reset receipt can disappear. Portable client exports still omit images.
