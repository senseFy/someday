# Contributing to Someday

Thanks for interest in improving Someday. This project is a local-first notes
and journal app with optional encrypted sync.

## License

By contributing, you agree that your contributions are licensed under the
[GNU General Public License v3.0 only](LICENSE) (`GPL-3.0-only`), the same
license that covers the project.

## Development setup

See [Development](docs/development.md) for toolchain requirements, local
service ports, application runners, and validation targets.

Typical loop:

```bash
make setup
make lint
make compile
make check
```

`make check` runs without local services, network accounts, or developer
credentials. Run the Docker-backed System V3 release gate separately:

```bash
make sync-v3-gate
```

Platform-specific smoke targets are documented in
[Development](docs/development.md). Signing and store publication are in
[Client release](docs/client-release.md).

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
- Synchronization tests follow `docs/sync-system-v3-test-strategy.md`: test each
  behavior at the lowest useful layer, keep end-to-end journeys few and
  complete, and use focused fixtures rather than a scenario DSL.
- Carefully review changes to dependency verification metadata, Gradle wrapper
  checksums, pinned CI action SHAs, and container digests.

## Pull requests

- Keep changes focused; prefer small PRs over mixed refactors.
- Match existing Kotlin / Compose style and package layout (`saien.someday`).
- Add or update tests when you change behavior, especially sync, crypto, merge,
  and migrations.
- Do not commit secrets, signing keys, App Store Connect credentials, keystores,
  local `.env` files, device databases, or personal notes data.
- Keep private operator runbooks outside the repository.
- Follow the project [Code of Conduct](CODE_OF_CONDUCT.md).

## Server releases

Open the maintainer workflow from the repository root:

```bash
make server-release
```

The menu provides planning, status, and rehearsal commands. A maintainer creates
and pushes the release tag. See [Server release](docs/server-release.md).
