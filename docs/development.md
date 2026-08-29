# Development

This guide covers the local toolchain, application runners, and repository-wide
validation. Production server deployment is documented separately in
[Self-hosting Someday](self-hosting.md).

## Requirements

- JDK 21 (Temurin, JBR, or OpenJDK) for Gradle, Kotlin, Compose, Desktop, and
  the server.
- Docker Engine and Docker Compose for local PostgreSQL and container-backed
  integration checks.
- Android SDK and an emulator or device for Android builds.
- Xcode and an iOS Simulator for Apple builds on macOS.

The monorepo uses Java 21 toolchains and `jvmTarget` throughout. Older JDKs
cannot load all project dependencies.

## Repository layout

- `shared:domain` — note, notebook, navigation, settings, location, and merge
  domain models.
- `shared:data` — local persistence, app-private media, SQLDelight
  repositories, encryption, recovery keys, settings, and export.
- `shared:sync` — System V3 clients, encrypted entity/media synchronization,
  and conflict resolution.
- `shared:ui` — shared Compose UI, controllers, and application shell.
- `app/android`, `app/ios`, `app/desktop` — platform entry points and adapters.
- `server` — Ktor sync/admin service.
- `integration-tests` — real self-hosted end-to-end validation.

## Setup

```bash
make setup
```

The manual equivalent is:

```bash
./gradlew --version
docker compose up -d postgres
```

The repository-root `compose.yaml` uses public development credentials. Do not
use it for production.

## Local services

| Service | Port | Start | Health check |
| --- | ---: | --- | --- |
| PostgreSQL | `54329` | `docker compose up -d postgres` | `pg_isready -h 127.0.0.1 -p 54329 -U someday` |
| Ktor server | `3180` | `make server-run` | `curl -sf http://127.0.0.1:3180/health` |

Stop PostgreSQL with `docker compose stop postgres`. Stop the foreground server
with the terminal interrupt.

Local server mode permits registration and may generate a temporary JWT
secret. Public deployments require the settings in
[Production configuration](self-hosting.md#production-configuration).

## Run applications

Open the interactive platform runner:

```bash
make run
```

Or select a platform directly:

```bash
make run-desktop
make run-android
make run-ios
```

Select an explicit device when needed:

```bash
make run-android DEVICE=emulator-5554
make run-ios DEVICE="iPhone 17"
```

The runner lists physical devices before emulators, reuses normal build
caches, and does not run `clean`. A physical iOS device requires a valid Xcode
signing team and provisioning profile. Export `IOS_DEVELOPMENT_TEAM` or
`IOS_TEAM_ID` when the runner should pass the team identifier to Xcode.

Manual desktop and server commands are:

```bash
./gradlew :app:desktop:run

SOMEDAY_PORT=3180 \
SOMEDAY_DB_URL=jdbc:postgresql://127.0.0.1:54329/someday \
./gradlew :server:run
```

For Android Studio, open the repository and select the `app:android` module.

## iOS Simulator host application

The installable host application lives in `iosApp/Someday.xcodeproj`. The
normal `make run-ios` path builds, installs, and launches it. The underlying
manual flow is:

```bash
SIMULATOR_UDID="$(xcrun simctl list devices available --json | python3 -c 'import json,sys; devices=json.load(sys.stdin)["devices"]; candidates=[device for runtime in sorted(devices) for device in devices[runtime] if device.get("isAvailable") and device.get("name")=="iPhone 17"]; candidates = candidates or [device for runtime in sorted(devices) for device in devices[runtime] if device.get("isAvailable") and device.get("name", "").startswith("iPhone")]; print(candidates[-1]["udid"] if candidates else "")')"
test -n "$SIMULATOR_UDID"

./gradlew :app:ios:linkDebugFrameworkIosSimulatorArm64 --max-workers=1
xcodebuild -project iosApp/Someday.xcodeproj -scheme Someday \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  -derivedDataPath iosApp/build build
xcrun simctl boot "$SIMULATOR_UDID" || true
xcrun simctl bootstatus "$SIMULATOR_UDID" -b
xcrun simctl install "$SIMULATOR_UDID" \
  iosApp/build/Build/Products/Debug-iphonesimulator/Someday.app
xcrun simctl launch "$SIMULATOR_UDID" saien.someday
```

## Test and validation

Run fast local checks while editing:

```bash
make lint
make compile
```

Run the full project checks before opening a pull request:

```bash
make check
```

`make check` covers source hygiene, unit and platform-host tests, migrations,
and Android lint without requiring local services.

Use the targeted gates when their subsystem changes:

```bash
make client-smoke
make shared-smoke
make server-test
make server-container-smoke
make integration-test
make sync-v3-gate

# Apple Silicon macOS with Xcode
make sync-v3-apple-gate
```

`make server-container-smoke` validates the production image, standalone
Compose setup, and operator commands. `make sync-v3-gate` provisions isolated
PostgreSQL, S3-compatible storage, and Ktor endpoints for end-to-end sync and
recovery tests. `make sync-v3-apple-gate` runs shared behavior and application
shell checks on an iOS Simulator.

Connected platform checks require their native runtimes:

```bash
make android-connected-test
make ios-simulator-test
```

## Continuous integration

The `CI` workflow runs the full Gradle checks and both System V3 release gates
for every pull request and push to `main`. Ubuntu runs the real
PostgreSQL/S3/server journeys; macOS runs shared and app-shell tests on an iOS
Simulator. CI does not depend on developer localhost services.

Workflow actions, container images, Gradle distribution checksums, and
dependency metadata are pinned. Review their source and checksums when updating
them, and keep strict dependency verification enabled.

Client signing, store uploads, and release versioning are documented in
[Client release](client-release.md). Server publication has a separate
[maintainer runbook](server-release.md).

## Engineering constraints

Database migrations, UI threading, and System V3 protocol rules are enforced
repository-wide. Read [agent.md](../agent.md) before changing those surfaces.
Database-specific guidance is in [Database migrations](database-migrations.md),
and the synchronization test model is in
[System V3 test strategy](sync-system-v3-test-strategy.md).
