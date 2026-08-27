# Server Release Plan

Status: local implementation complete. Managed-provider evidence requires
dedicated test resources.

## 1. Decisions

### Release

- Server releases use `server-vX.Y.Z`; client releases keep `vX.Y.Z`.
- GHCR publishes `ghcr.io/sensefy/someday-server:X.Y.Z` for AMD64 and ARM64.
- Deployment uses the readable tag together with the published image digest.
- Only exact tags are published. A defective image gets a new version instead
  of replacing an existing tag.

### Deployment

- The supported topologies are PostgreSQL 17 plus filesystem media, and
  PostgreSQL 17 plus private S3-compatible media.
- Release Compose uses the published image. Source builds use a separate
  override and remain available from the exact tag with Java 21.
- Docker is the only prebuilt distribution. The tested minimum is Docker Engine
  24 and Docker Compose 2.20.
- Release Compose publishes the server port on host loopback by default. Caddy
  is a host-native HTTPS example, not part of Compose.

### PostgreSQL

- The first release supports PostgreSQL 17 only.
- The server uses a direct connection. Transaction-mode poolers are unsupported
  because RLS uses session state.
- Production roles must be neither superuser nor `BYPASSRLS`.
- External connections verify TLS certificates and hostnames.
- PlanetScale PostgreSQL 17 direct on port `5432` is the first named managed
  profile. No PlanetScale-specific server code is added.

### Media

- Filesystem and S3 continue to share the existing `MediaBlobStore` boundary.
- Production S3 endpoint overrides require HTTPS.
- Startup performs one bounded media-store check before HTTP listen.
- MinIO runs the provider-neutral contract in CI with the 4 MiB (4,194,304-byte)
  encoded-original and 4,198,444-byte ciphertext limits.
- Cloudflare R2 is the first named candidate S3 profile. It uses a private
  dedicated bucket, a non-admin `Object Read & Write` token scoped to that
  bucket, indefinite Bucket Lock on `media/v1/`, and no expiry rule for that
  prefix.
- Generic S3 configuration accepts services that satisfy the storage contract.
  A provider is named as verified only after its live gate passes.

### Recovery

- PostgreSQL and media are one server recovery unit.
- Client portable export still excludes image bytes.
- Server recovery covers both text and images and is tested before any client
  can upload to the restored server.
- Readable recovery requires at least one intact paired device; the server does
  not hold the workspace key.

### Upgrade

- Within one server major version, an older Compose file and `.env` remain valid
  when only `SOMEDAY_IMAGE` changes.
- From the second release onward, every candidate upgrades non-empty data from
  the immediately preceding published server version.
- Flyway remains forward-only. An old image refuses a newer schema; database
  rollback restores the pre-upgrade PostgreSQL and media recovery unit.

### Operator guide

- Standalone and external storage have separate short quickstarts.
- Each quickstart ends at HTTPS, first administrator, and client connection.
- Backup setup and an isolated restore exercise are a separate checklist to
  complete before storing real data.

## 2. Implementation and acceptance

### Phase A: production runtime

Implement:

- move standalone and test infrastructure from PostgreSQL 16 to 17;
- make the published image default to production mode;
- share database-role checks across `server` and `bootstrap-admin`;
- enforce external PostgreSQL TLS and HTTPS for production S3 endpoint
  overrides;
- add the filesystem/S3 startup check; and
- run the production-mode MinIO fixture over TLS.

Accept when:

- the complete System V3 gate passes on PostgreSQL 17 and HTTPS MinIO;
- unsafe database roles, invalid database or media credentials, TLS validation
  failures, inaccessible configured stores, and immutability violations fail
  before HTTP listen; and
- valid standalone and external configurations start successfully.

Existing unreleased PostgreSQL 16 development volumes are recreated explicitly
after exporting anything worth keeping. No pre-release volume upgrader is added.

### Phase B: release artifact

Implement:

- add the `server-vX.Y.Z` GHCR workflow and narrow client tag triggers;
- publish one AMD64/ARM64 image with its digest and OCI metadata;
- split image-only release Compose from the source-build override; and
- add minimum-version and clean-directory deployment checks.

Accept when:

- a clean anonymous environment can pull the exact image;
- both architectures actually start;
- either topology starts outside the repository from its copied `deploy/`
  directory;
- the source override builds and starts from an exact source tag; and
- GitHub Release publication waits for all public-image checks.

### Phase C: migration and recovery

Implement:

- freeze historical Flyway migrations and reject unknown future migrations in
  both server entry points;
- verify the exact RLS table/policy set and retain cross-tenant behavior tests;
- require tenant-row migrations to keep their RLS wildcard scope in the same
  transaction and pass the non-empty previous-release upgrade gate;
- add a non-empty standalone backup/restore gate; and
- add the same-major upgrade and rollback runbook.

Accept when:

- restored ownership and Flyway history are correct, and database/media counts
  are non-zero and match the source before client sync;
- a content-empty paired client can pull the original text and image while all
  client writes are rejected;
- missing media makes `verify-media-integrity` exit with status `2`;
- both entry points reject a future schema; and
- an actual previous-version deployment upgrades by changing only the image.

### Phase D: provider evidence and documentation

Implement:

- validate PlanetScale PostgreSQL 17 direct with TLS, the restricted role,
  migrations, RLS, sync, and isolated restore;
- validate R2 with the S3 contract, private access, Bucket Lock, retention, and
  an isolated restore from an off-provider recovery copy;
- document the generic S3 capability contract;
  and
- publish the two quickstarts and the pre-data recovery checklist.

Accept when:

- both named provider profiles have retained live evidence;
- the R2 gate restores into an isolated bucket and reads non-empty text and an
  image through a paired client before client writes are enabled;
- every public command works from a clean directory at the release tag; and
- all architecture, self-hosting, Compose, and `.env` documentation describes
  the same contract.

## 3. Required inputs

- GHCR package access and the one-time public visibility change.
- AMD64 and ARM64 runtime environments.
- A PlanetScale PostgreSQL 17 direct test database and isolated restore target.
- R2 resources matching the verified profile and an off-provider backup target.
- An intact paired client for readable recovery verification.
- A public, private, or VPN-resolvable hostname for the HTTPS quickstart.

Local implementation can proceed through Phase C before the managed-provider
credentials are available.

## 4. Non-goals

- Kubernetes, Helm, Terraform, or cloud-platform deployment templates.
- Automatic DNS, certificate, database, bucket, or IAM provisioning.
- Floating image tags, automatic updates, or OS-specific installers.
- Transaction-pooler support, multiple replicas, or provider failover.
- Automatic backup scheduling, PITR orchestration, or a custom backup format.
- Provider plugins or provider-specific storage implementations.
- An offline workspace-key recovery package.
- Media deletion, garbage collection, public object URLs, multipart upload,
  video, or general attachments.
