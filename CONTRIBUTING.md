# Contributing to Someday

Thanks for interest in improving Someday. This project is a local-first notes
and journal app with optional encrypted sync.

## License

By contributing, you agree that your contributions are licensed under the
[GNU General Public License v3.0 only](LICENSE) (`GPL-3.0-only`), the same
license that covers the project.

## Development setup

See [README.md](README.md) for toolchain requirements, `make setup`, local
service ports, and common build targets.

Typical loop:

```bash
make setup
make lint
make compile
make check
```

`make check` is hermetic: it must not require localhost services, network
accounts, or developer credentials. The Docker-backed System V3 release gate is
explicit and separate:

```bash
make sync-v3-gate
```

Platform-specific smoke and packaging targets are documented in the README.

## Engineering rules

Repository-wide constraints for schema, UI threading, and System V3 live in
[agent.md](agent.md). Protocol and migration details live under `docs/`.

Important defaults:

- Client schema changes go through SQLDelight migrations; server schema through
  Flyway. Do not patch schema from ad-hoc startup code.
- Keep Compose and main-thread UI free of repository, file, network, and secure
  storage I/O. Controllers own dispatcher boundaries for suspend IO.
- Protocol changes must update the matching `docs/` specification in the same
  change and include tests where the gate docs require them.
- Synchronization tests follow `docs/sync-system-v3-test-strategy.md`: prove an
  invariant at its lowest owning layer, keep real E2E journeys few and complete,
  and use explicit fixtures rather than a scenario DSL.
- Dependency verification metadata, Gradle wrapper checksums, pinned CI action
  SHAs, and container digests are security-sensitive review surfaces. Never
  regenerate or weaken them without reviewing the exact dependency update.

## Pull requests

- Keep changes focused; prefer small PRs over mixed refactors.
- Match existing Kotlin / Compose style and package layout (`saien.someday`).
- Add or update tests when you change behavior, especially sync, crypto, merge,
  and migrations.
- Do not commit secrets, signing keys, App Store Connect credentials, keystores,
  local `.env` files, device databases, or personal notes data.
- Do not reintroduce private operator runbooks under `.factory/` or similar
  ignored paths.
- Follow the project [Code of Conduct](CODE_OF_CONDUCT.md).

## Scope notes

- Sync is the single self-hosted **System V3** contract: the internal entity
  DAG plus one immutable encrypted object per media asset. The canonical route
  roots are `/sync/v3/workspaces/{workspaceId}/entities` and
  `/sync/v3/workspaces/{workspaceId}/media`. See
  `docs/sync-system-v3-spec.md`.
- Static JPEG, PNG, and WebP originals are supported up to 4 MiB and 12MP.
  General files, SVG, animation, video, and map SDKs remain outside the initial
  product scope.
- Portable export/restore intentionally omits image bytes. Self-hosted operator
  recovery must preserve PostgreSQL and the configured media store together.
