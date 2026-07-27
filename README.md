# Someday

Someday is a Kotlin Multiplatform, Compose Multiplatform, local-first notes and journal app. It includes notebook and note management, Markdown editing and preview, version history, conflict handling, memories by calendar date, location capture, settings, encrypted sync objects, WebDAV sync, and a self-hosted Ktor sync/admin server.

## License

Someday is free software licensed under the [GNU General Public License v3.0](LICENSE).

You may run, study, share, and modify it under the terms of that license. If you distribute modified versions (including as part of a larger work that links or packages this software), you must provide corresponding source under GPL-3.0 as required by the license.

## Status

- **Local-first notes, memories, Markdown, export, and recovery-key workflows** are the core product surface.
- **Sync** is **System V2** only: immutable workspace-entity DAG + epoch/checkpoint on WebDAV and self-hosted transports. See `docs/sync-system-v2-spec.md`.
- **Device pairing** uses a one-use encrypted invitation transferred by QR or
  a checksummed high-entropy manual token. WebDAV and the self-hosted server
  never receive the workspace key in plaintext. See
  `docs/workspace-pairing-protocol.md`.
- **Attachments and map SDKs** are intentionally out of scope for the current product.
- Disaster-recovery WebDAV **backup** is a separate action from incremental sync.

## Contributing and security

- How to build, test, and open changes: [CONTRIBUTING.md](CONTRIBUTING.md)
- Community expectations: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- Engineering constraints (schema, UI threading, Sync V2): [agent.md](agent.md)
- Vulnerability reports: [SECURITY.md](SECURITY.md) (please do not file public issues for security bugs)
- Self-hosted production contract: [docs/self-hosting.md](docs/self-hosting.md)

## Repository layout

- `shared:domain` — shared note, notebook, navigation, settings, location, and merge domain models.
- `shared:data` — local-first persistence, SQLDelight repositories, encryption/recovery key support, settings, and export helpers.
- `shared:sync` — encrypted sync object handling, WebDAV sync, self-hosted sync clients, and conflict resolution.
- `shared:ui` — shared Compose UI controllers and app shell.
- `app:android`, `app:ios`, `app:desktop` — platform entry points and platform adapters.
- `server` — Ktor API/admin service backed by PostgreSQL.
- `integration-tests` — real WebDAV and self-hosted end-to-end validation.

## Requirements

- JDK suitable for the Gradle wrapper and Kotlin/Compose toolchain.
- Docker and Docker Compose for PostgreSQL and WebDAV integration services.
- Android SDK/emulator for Android smoke or instrumented tests.
- Xcode/iOS Simulator on macOS for iOS simulator tests.

## Local services and ports

The local development stack uses these loopback-only ports. `compose.yaml`
contains intentionally public local credentials and must not be deployed as a
production stack; see [the self-hosting guide](docs/self-hosting.md).

| Service | Port | Start command | Health check |
| --- | ---: | --- | --- |
| PostgreSQL | `54329` | `docker compose up -d postgres` | `pg_isready -h 127.0.0.1 -p 54329 -U someday` |
| WebDAV | `3182` | `docker compose up -d webdav` | `curl -sf -X OPTIONS http://127.0.0.1:3182/` |
| Ktor server | `3180` | `SOMEDAY_PORT=3180 SOMEDAY_DB_URL=jdbc:postgresql://127.0.0.1:54329/someday ./gradlew :server:run` | `curl -sf http://127.0.0.1:3180/health` |

Stop containers with `docker compose stop postgres webdav`. Stop the Ktor server with your terminal interrupt, or by terminating the process listening on port `3180`.

## Setup

```bash
make setup
```

Manual equivalent:

```bash
./gradlew --version
docker compose up -d postgres webdav
```

Start the self-hosted server when testing API/admin or self-hosted sync flows:

```bash
make server-run
```

Manual equivalent:

```bash
SOMEDAY_PORT=3180 SOMEDAY_DB_URL=jdbc:postgresql://127.0.0.1:54329/someday ./gradlew :server:run
```

Local mode allows registration and generates an ephemeral JWT secret unless
you provide one. Production deployments must set
`SOMEDAY_DEPLOYMENT_MODE=production` and satisfy the fail-closed configuration
contract in [docs/self-hosting.md](docs/self-hosting.md).

## Run

```bash
make

# Interactive platform/device runner
make run

# Or run a platform directly
make run-desktop
make run-android
make run-ios

# Optional explicit device selection
make run-android DEVICE=emulator-5554
make run-ios DEVICE="iPhone 17"
```

The runner prompts for a platform when one is not provided, prompts for Android/iOS devices when more than one is available, and uses Up/Down arrows plus Enter for selection. Device menus show physical devices before emulators/simulators, distinguish them with icons, and shorten device IDs to their last few characters for display. It reuses the normal Gradle and Xcode caches and does not run `clean`; remove build directories manually if a fresh build is needed. Running on a physical iOS device requires a valid Xcode signing team/provisioning profile; export `IOS_DEVELOPMENT_TEAM=<team-id>` or `IOS_TEAM_ID=<team-id>` in the current environment when you want the runner to pass a team id to Xcode.

Manual equivalents:

```bash
# Desktop app
./gradlew :app:desktop:run

# Server only
SOMEDAY_PORT=3180 SOMEDAY_DB_URL=jdbc:postgresql://127.0.0.1:54329/someday ./gradlew :server:run
```

For Android Studio, open the project and use the `app:android` module. Desktop produces a JVM app and package-readiness checks for Windows/Linux; signing, notarization, and store packaging are outside the first release scope.

### Application identities

Someday uses one stable product identity across its mobile and Apple desktop distributions. Keep these identifiers unchanged after public distribution:

| Platform | Stable distribution identity |
| --- | --- |
| Android | Application ID `saien.someday` |
| iOS | Bundle ID `saien.someday` |
| macOS | Bundle ID `saien.someday` |
| Windows | MSI upgrade UUID `F72E91C4-8CCC-4883-BAC6-157C366E7E3D` |
| Linux | Package name `someday` |

All Kotlin packages and source-code namespaces use `saien.someday` as their root; these source namespaces are not store or installer identities. macOS intentionally shares the iOS bundle ID so both remain platform versions of the same Apple product; use a different bundle ID only if they must become independently distributed App Store products.

Android `versionName`, iOS `MARKETING_VERSION`, and the macOS/Windows/Linux Desktop `packageVersion` share one user-visible release version. Android `versionCode` and iOS `CURRENT_PROJECT_VERSION` are independent monotonically increasing store build numbers, because the stores may require a different number of upload attempts. Use `make bump-app-version VERSION_NAME=x.y.z` for a new cross-platform release, or omit `VERSION_NAME` to increment the highest current patch version. Use `make bump-mobile-build-version` only when resubmitting the same visible mobile release.

## Continuous integration

The `CI` workflow runs the hermetic Gradle `check` contract and the complete
Sync V2 release gate for every pull request and every push to `main`. The gate
starts pinned disposable PostgreSQL and WebDAV containers; it never contacts
developer localhost services. The separate `Android` workflow builds a debug
APK when a tag is pushed or when the workflow is run manually.

Workflow actions are pinned to immutable commit SHAs. The Gradle wrapper JAR
matches the declared Gradle release, the distribution checksum is pinned, and
`gradle/verification-metadata.xml` verifies resolved dependency checksums.
Dependabot proposes Gradle and Actions updates for review. Container images use
reviewed immutable digests and are updated manually.

Before signing or uploading a release, run the cross-platform release preflight
on macOS:

```bash
make release-readiness
```

It resolves the Android release runtime, compiles the unsigned Android Release
variant, validates the Compose Desktop packaging runtime, and builds the iOS
device Release framework/resources with strict dependency verification. It
also verifies that every generated shipping BuildConfig enables V2
first-epoch activation while development activation remains off. Low-level
Gradle properties default off; canonical Android, iOS, and macOS release
entrypoints supply the accepted shipping value explicitly, and direct release
packaging refuses an omitted value. The preflight does not access signing
credentials, notarize artifacts, or contact an app store.

To trigger an Android package build from Git:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow runs this Gradle task:

```bash
./gradlew :app:android:assembleDebug --stacktrace
```

The workflow uploads `app/android/build/outputs/apk/debug/android-debug.apk` as the `someday-android-debug-apk` artifact. Manual builds can be started from the `Android` workflow in the GitHub Actions tab.

Makefile wrappers:

```bash
make android-ci-trigger
make android-ci-list
make android-ci-watch RUN_ID=<run-id>
make android-ci-download RUN_ID=<run-id>
```

### Android Google Play release

Android release bundles use the `saien.someday` application ID. Release signing credentials must be provided explicitly and must stay outside the repository:

```bash
export SOMEDAY_ANDROID_KEYSTORE_PATH=/absolute/path/to/someday-release.jks
export SOMEDAY_ANDROID_KEYSTORE_PASSWORD=<store-password>
export SOMEDAY_ANDROID_KEY_ALIAS=<key-alias>
export SOMEDAY_ANDROID_KEY_PASSWORD=<key-password>

# Required for upload unless GOOGLE_APPLICATION_CREDENTIALS or GOOGLE_PLAY_ACCESS_TOKEN is set
export ANDROID_PLAY_SERVICE_ACCOUNT_JSON=/absolute/path/to/google-play-service-account.json

# Build, sign, and verify without uploading
make android-release-play

# Build and upload to Google Play internal testing
make android-release-play UPLOAD=yes

# Verify and upload an existing release AAB
make android-upload-play
```

The upload tooling never searches a home-directory default for credentials. Provide `ANDROID_PLAY_SERVICE_ACCOUNT_JSON`, use the standard `GOOGLE_APPLICATION_CREDENTIALS`, or pass a short-lived `GOOGLE_PLAY_ACCESS_TOKEN` with the `androidpublisher` scope. Uploads default to the `internal` track and `completed` status. Production requires `ANDROID_PLAY_TRACK=production ANDROID_PLAY_CONFIRM_PRODUCTION=yes`; use `ANDROID_PLAY_DRY_RUN=yes` to verify the artifact and release plan without Google API calls.

Before the first upload, create the app in Play Console and choose the option to provide an existing app signing key when cross-channel APK updates must remain compatible. Import that key as Someday's **app signing key**, not only as its upload key, so externally distributed and Play-delivered APKs share the same signing certificate. Keep the private key backed up securely and never commit it.

iOS CI is intentionally not enabled as a required GitHub-hosted job yet. GitHub-hosted macOS runners can build iOS simulator targets with preinstalled Xcode, but physical-device packaging needs signing certificates, provisioning profiles, and team configuration in repository secrets. Add a separate macOS workflow when those release credentials and cost expectations are ready.

### iOS App Store / TestFlight release

Local App Store packaging lives in `scripts/release-ios.sh` and is wrapped by Makefile targets. The script prebuilds the Release `SomedayIos.framework` with developer options disabled, archives the `Someday` Xcode scheme, and exports an App Store Connect IPA. Interactive runs can optionally bump the iOS build number and choose direct TestFlight upload before export, so Xcode exports the archive only once.

```bash
export IOS_TEAM_ID=YOUR_TEAM_ID

# Archive + export a local IPA
make ios-release

# Archive + upload to TestFlight
make ios-upload-testflight

# Archive only / export only / upload an existing archive
make ios-archive
make ios-export
make ios-upload-archive

# Bump CURRENT_PROJECT_VERSION and commit
make bump-ios-version

# Bump Android versionCode and commit
make bump-android-version

# Bump mobile build/code numbers without changing the visible version
make bump-mobile-build-version

# Align Android/iOS/Desktop visible versions and bump mobile build/code
make bump-app-version VERSION_NAME=1.0.5
```

Useful optional variables:

```bash
export IOS_BUILD_NUMBER=12
export IOS_MARKETING_VERSION=1.0.1
export IOS_BUMP_VERSION=yes # or no
export IOS_ARCHIVE_PATH="/absolute/path/to/Someday-Release.xcarchive"
export IOS_EXPORT_PATH="/absolute/path/to/ipa-output"
export IOS_PROVISIONING_PROFILE_SPECIFIER="<App Store profile name>"
export IOS_UPLOAD=yes
```

Upload auth defaults to App Store Connect API key mode. Keep the private key outside the repository, restrict it to the current user, and export only its path and identifiers:

```bash
export APP_STORE_CONNECT_API_KEY_PATH="/absolute/path/outside/repository/AuthKey_XXXXXXXXXX.p8"
chmod 600 "$APP_STORE_CONNECT_API_KEY_PATH"
export APP_STORE_CONNECT_API_KEY_ID="XXXXXXXXXX"
export APP_STORE_CONNECT_API_ISSUER_ID="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
```

Credential input is environment-only at the Make-wrapper boundary, so the release-script command does not contain the source key path or account identifiers. Before invoking Xcode, the script copies the key into a private temporary directory and redacts Xcode-required identifiers and host paths from output. It refuses API keys stored inside the repository or readable by group/other users. For an explicit Xcode Accounts-based upload, export `IOS_UPLOAD_AUTH_MODE=accounts` without the API key variables. Xcode certificate/profile updates are disabled by default; enable them only when needed with `IOS_ALLOW_PROVISIONING_UPDATES=true`. Ensure the local Apple Distribution certificate and App Store provisioning profile for `saien.someday` are available before packaging.

### iOS Simulator host app

The installable iOS host app lives in `iosApp/Someday.xcodeproj`. It links and embeds the generated `SomedayIos.framework`, then presents the shared Compose `MainViewController()`.

```bash
SIMULATOR_UDID="$(xcrun simctl list devices available --json | python3 -c 'import json,sys; devices=json.load(sys.stdin)["devices"]; candidates=[device for runtime in sorted(devices) for device in devices[runtime] if device.get("isAvailable") and device.get("name")=="iPhone 17"]; candidates = candidates or [device for runtime in sorted(devices) for device in devices[runtime] if device.get("isAvailable") and device.get("name", "").startswith("iPhone")]; print(candidates[-1]["udid"] if candidates else "")')"
test -n "$SIMULATOR_UDID"

./gradlew :app:ios:linkDebugFrameworkIosSimulatorArm64 --max-workers=1
xcodebuild -project iosApp/Someday.xcodeproj -scheme Someday -configuration Debug -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" -derivedDataPath iosApp/build build
xcrun simctl boot "$SIMULATOR_UDID" || true
xcrun simctl bootstatus "$SIMULATOR_UDID" -b
xcrun simctl install "$SIMULATOR_UDID" iosApp/build/Build/Products/Debug-iphonesimulator/Someday.app
xcrun simctl launch "$SIMULATOR_UDID" saien.someday
```

## Test and validation

Lightweight checks:

```bash
make lint
make compile
```

Full project validation:

```bash
make check
```

`make check` is hermetic: it runs source hygiene, unit tests, platform-host
tests, migration checks, and Android lint without assuming PostgreSQL, WebDAV,
or a server on fixed localhost ports.

Targeted smoke and integration checks:

```bash
make client-smoke
make shared-smoke
make server-test
make integration-test
make sync-v2-gate
```

`make integration-test` contains only hermetic repository topology checks.
`make sync-v2-gate` provisions isolated PostgreSQL, WebDAV, and Ktor endpoints,
then runs the no-skip real remote corpus. `make real-remote-test` and
`make validate` are lower-level entry points for an explicitly supplied
`SOMEDAY_*` live-service environment.

Android connected and iOS simulator checks require their platform runtimes:

```bash
make android-connected-test
make ios-simulator-test
```

## Platform notes

- The app is local-first: clients use local SQLite/SQLDelight as the primary store and sync in the background.
- Database evolution rules are documented in `docs/database-migrations.md`; local schema changes go through SQLDelight migrations and server schema changes go through Flyway.
- Sync payloads are encrypted client-side before upload; the self-hosted service does not need plaintext note bodies.
- WebDAV and self-hosted sync cover append-only version objects, tombstones, conflict handling, and recovery-key workflows.
- Location capture records coordinates and place text without requiring a map SDK.
- The first release targets Android, iOS, macOS, Windows, and Linux, with JVM Desktop as the primary desktop runtime.
