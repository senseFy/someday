# Documentation

This is the complete index of Someday's maintained project documentation and
the shared rules for adding or changing it. Specifications and guides describe
the current repository, not necessarily the latest published release.

## Documentation rules

1. **One authoritative definition per concern.** Specifications own protocol,
   behavior and architecture contracts. Guides and plans link to those contracts
   instead of maintaining competing copies.
2. **Separate current behavior from proposed changes.** Use `specs/` for current
   contracts, `guides/` for development/testing/operations instructions, and
   `plans/` for work under discussion or implementation. State each document's
   scope; a proposal must not imply that its feature already exists.
3. **Give plans an explicit status.** Use `draft`, `accepted`, `completed`, or
   `cancelled`. `accepted` means the design is approved, not implemented or
   released. Record remaining acceptance work. On completion, put lasting rules
   and instructions in the owning specs/guides, then move the plan to `archive/`
   with links to its successors. Create that directory only when needed. Do not
   archive an active specification merely because its name has an older version.
4. **Update documentation with behavior.** Change the owning spec or guide in
   the same change as the behavior it describes. Small changes normally update
   existing documents; a new feature does not automatically need a design, plan
   and report. Split a substantial plan only when the files have distinct roles.
5. **Keep navigation working.** Add every maintained document to this index.
   Use lowercase, hyphenated filenames and relative Markdown links. Group a
   multi-document plan by feature. When moving a file, update links and literal
   paths in guidance, source, scripts, tests and CI, and verify those references.
6. **Keep working logs and private data out.** Temporary diagnostics, screenshots
   and progress transcripts are not maintained documentation. Do not commit
   personal notes, device databases, credentials, recovery codes or private
   operator configuration. Use synthetic examples and safe evidence references.

The repository root retains project entry points and policies such as
`README.md`, `CONTRIBUTING.md`, `SECURITY.md` and `agent.md`.

## Current specifications

The index is grouped by the rules above. Existing files are still at their
original paths until the separate directory-reorganization change.

| Document | Scope |
| --- | --- |
| [System V3](sync-system-v3-spec.md) | Product synchronization contract and client/server lifecycle. |
| [Entity DAG V2 subsystem](sync-system-v2-spec.md) | Active internal DAG protocol; not an obsolete product mode. |
| [Self-hosted media V3](self-hosted-media-v3.md) | Encrypted media objects, publication and bounds. |
| [Workspace pairing](workspace-pairing-protocol.md) | Device-to-device workspace joining and replacement. |
| [Workspace recovery](workspace-recovery-protocol.md) | Recovery codes, envelopes and workspace replacement. |
| [Day One import](day-one-import.md) | Supported export/import compatibility profile. |
| [Server storage architecture](server-storage-architecture.md) | Persistence topology and storage/security boundaries. |

## Guides

| Document | Scope |
| --- | --- |
| [Development](development.md) | Local toolchains, application runners and checks. |
| [Database migrations](database-migrations.md) | SQLDelight/Flyway schema evolution and verification. |
| [System V3 test strategy](sync-system-v3-test-strategy.md) | Test layers and acceptance evidence. |
| [Managed storage profile gates](managed-storage-profile-gates.md) | Provider-specific storage certification. |
| [Self-hosting](self-hosting.md) | Deployment overview and production configuration. |
| [Standalone deployment](self-hosting-standalone.md) | Single-host Docker setup. |
| [External storage deployment](self-hosting-external.md) | External PostgreSQL and S3-compatible media. |
| [Server backup and recovery](server-backup-and-recovery.md) | Coordinated database/media recovery. |
| [Server upgrades](server-upgrades.md) | Upgrade and rollback procedure. |
| [Server release](server-release.md) | Maintainer image and deployment-bundle publication. |
| [Client release](client-release.md) | Client versions and platform publication. |

## Active plans

| Document | Remaining work |
| --- | --- |
| [Client image support](client-image-support-plan.md) | Records implementation and automated gates as complete; real-client UI acceptance remains before release. |
