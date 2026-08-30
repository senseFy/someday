# Someday

Someday is a Kotlin Multiplatform, local-first notes and journal application
for Android, iOS, macOS, Windows, and Linux. It combines Markdown editing,
history, memories, location capture, and encrypted synchronization through a
Someday Server operated by the user or a service provider.

## Status

- Notes, notebooks, Markdown, version history, memories, search, settings,
  export, and workspace-recovery workflows are implemented on the shared
  client.
- Someday Server synchronization keeps encrypted notes and images consistent
  across trusted devices.
- An existing device can securely transfer the workspace key when pairing a
  new device; the server cannot read that key or the synchronized content.
- A user-held 128-bit recovery code can restore the workspace without another
  device. The server stores only an opaque wrapped-key envelope and never
  receives the code or plaintext workspace key.
- If every device holding the key, the current recovery code, and every
  readable plaintext backup are lost, neither the service operator nor a
  self-hosted administrator can recover the ciphertext.
- Static JPEG, PNG, and WebP inputs are normalized into app-owned image assets
  bounded to 4 MiB and 12 megapixels. Other files, SVG, animation, and video
  are not currently supported.
- Portable client export excludes image bytes and workspace keys. Server
  recovery therefore preserves PostgreSQL and the configured media store as
  one unit.

## Self-hosting

Start with [Self-hosting Someday](docs/self-hosting.md). It explains the
required resources and links to two Docker paths:

- [External PostgreSQL and S3](docs/self-hosting-external.md), recommended for
  production; or
- [Standalone Docker](docs/self-hosting-standalone.md) for a smaller
  installation on one host.

Published server images and deployment bundles are available from
[GitHub Releases](https://github.com/senseFy/someday/releases).

## Documentation

| Topic | Document |
| --- | --- |
| Local development and tests | [Development](docs/development.md) |
| Client versions and store publication | [Client release](docs/client-release.md) |
| Server deployment and operations | [Self-hosting Someday](docs/self-hosting.md) |
| Server persistence model | [Server Storage Architecture](docs/server-storage-architecture.md) |
| Backup and recovery | [Server Backup and Recovery](docs/server-backup-and-recovery.md) |
| System V3 synchronization | [System V3 specification](docs/sync-system-v3-spec.md) |
| Device pairing | [Workspace pairing protocol](docs/workspace-pairing-protocol.md) |
| Workspace recovery | [Workspace recovery protocol](docs/workspace-recovery-protocol.md) |
| Database evolution | [Database migrations](docs/database-migrations.md) |

## Development

The shared build requires JDK 21. Android and Apple targets additionally need
their native SDKs; container-backed tests and local server work require Docker.

```bash
make setup
make run
make check
```

Use `make run-desktop`, `make run-android`, or `make run-ios` to select a
platform directly. The complete environment, runner, and validation reference
is in [Development](docs/development.md).

## Contributing and security

- Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a change.
- Follow the project [Code of Conduct](CODE_OF_CONDUCT.md).
- Apply the schema, UI-threading, and System V3 constraints in
  [agent.md](agent.md).
- Report vulnerabilities through [SECURITY.md](SECURITY.md), not a public
  issue.

## License

Someday is free software licensed under the
[GNU General Public License v3.0](LICENSE).

You may run, study, share, and modify it under that license. Distributing a
modified version requires the corresponding source as specified by GPL-3.0.
