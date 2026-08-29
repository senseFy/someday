# Client release

This is the maintainer runbook for client versions, signed packages, and test
track publication. Server images use the separate
[server release workflow](server-release.md).

## Application identities

Keep these identities stable after public distribution:

| Platform | Distribution identity |
| --- | --- |
| Android | Application ID `saien.someday` |
| iOS | Bundle ID `saien.someday` |
| macOS | Bundle ID `saien.someday` |
| Windows | MSI upgrade UUID `F72E91C4-8CCC-4883-BAC6-157C366E7E3D` |
| Linux | Package name `someday` |

All Kotlin packages use `saien.someday` as their namespace. macOS and iOS share
one bundle ID because they are platform versions of the same Apple product.

## Versions

Android `versionName`, iOS `MARKETING_VERSION`, and Desktop `packageVersion`
share one user-visible version. Android `versionCode` and iOS
`CURRENT_PROJECT_VERSION` are independent, monotonically increasing store
build numbers.

Set the next shared client version and increment both mobile build numbers:

```bash
make bump-app-version VERSION_NAME=1.0.5
```

Omit `VERSION_NAME` to increment the highest current patch version. When
resubmitting the same visible version, increment only the native build numbers:

```bash
make bump-ios-version
make bump-android-version
make bump-mobile-build-version
```

These commands change source version files and commit the result. Release
packaging commands do not change versions.

## Preflight

Before signing or uploading on macOS, run:

```bash
make release-readiness
```

This resolves the Android release runtime, compiles the unsigned Android
Release variant, validates Desktop packaging, and builds the iOS device
Release framework and resources. It does not read signing credentials,
notarize artifacts, or contact an app store.

## Android CI artifact

The `Android` workflow builds a debug APK when a client tag is pushed or the
workflow is run manually:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow uploads `app/android/build/outputs/apk/debug/android-debug.apk` as
the `someday-android-debug-apk` artifact. Inspect or download a run with:

```bash
make android-ci-trigger
make android-ci-list
make android-ci-watch RUN_ID=<run-id>
make android-ci-download RUN_ID=<run-id>
```

The debug artifact is not a Google Play release.

## Android Google Play

Android release bundles use the `saien.someday` application ID. Keep signing
credentials outside the repository and provide them explicitly:

```bash
export SOMEDAY_ANDROID_KEYSTORE_PATH=/absolute/path/to/someday-release.jks
export SOMEDAY_ANDROID_KEYSTORE_PASSWORD=<store-password>
export SOMEDAY_ANDROID_KEY_ALIAS=<key-alias>
export SOMEDAY_ANDROID_KEY_PASSWORD=<key-password>
```

For upload authentication, provide one of:

- `ANDROID_PLAY_SERVICE_ACCOUNT_JSON`;
- the standard `GOOGLE_APPLICATION_CREDENTIALS`; or
- a short-lived `GOOGLE_PLAY_ACCESS_TOKEN` with the `androidpublisher` scope.

Build and verify without uploading:

```bash
make android-release-play
```

Build and upload to Google Play internal testing:

```bash
export ANDROID_PLAY_SERVICE_ACCOUNT_JSON=/absolute/path/to/google-play-service-account.json
make android-release-play-upload
```

Verify and upload an existing release AAB:

```bash
make android-upload-play
```

Uploads default to the `internal` track and `completed` status. Production
requires both explicit settings:

```bash
ANDROID_PLAY_TRACK=production \
ANDROID_PLAY_CONFIRM_PRODUCTION=yes \
make android-release-play-upload
```

Use `ANDROID_PLAY_DRY_RUN=yes` to validate the artifact and plan without
calling Google APIs.

Before the first upload, create the application in Play Console. If packages
distributed outside Play must remain upgrade-compatible, import the existing
key as the Play **app signing key**, not only as its upload key. Back up the
private key and never commit it.

## iOS App Store and TestFlight

Local packaging is implemented by `scripts/release-ios.sh` and its Make
targets. It builds the Release framework, archives the `Someday` Xcode scheme,
and exports an App Store Connect IPA.

Set the Apple development team:

```bash
export IOS_TEAM_ID=YOUR_TEAM_ID
```

Choose one explicit operation:

```bash
# Archive and export a local IPA
make ios-release

# Archive and upload to TestFlight
make ios-upload-testflight

# Individual stages and recovery paths
make ios-archive
make ios-export
make ios-upload-archive
```

Optional package inputs are:

```bash
export IOS_BUILD_NUMBER=12
export IOS_MARKETING_VERSION=1.0.1
export IOS_ARCHIVE_PATH="/absolute/path/to/Someday-Release.xcarchive"
export IOS_EXPORT_PATH="/absolute/path/to/ipa-output"
export IOS_PROVISIONING_PROFILE_SPECIFIER="<App Store profile name>"
```

Upload authentication defaults to an App Store Connect API key. Keep its
private key outside the repository and restrict it to the current user:

```bash
export APP_STORE_CONNECT_API_KEY_PATH="/absolute/path/outside/repository/AuthKey_XXXXXXXXXX.p8"
chmod 600 "$APP_STORE_CONNECT_API_KEY_PATH"
export APP_STORE_CONNECT_API_KEY_ID="XXXXXXXXXX"
export APP_STORE_CONNECT_API_ISSUER_ID="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
```

The Make wrapper accepts credentials through the environment. Before invoking
Xcode, the release script copies the private key to a restricted temporary
directory and redacts Xcode-required identifiers and host paths from output.
It rejects keys stored inside the repository or readable by other users.

To use credentials already configured in Xcode Accounts, set:

```bash
export IOS_UPLOAD_AUTH_MODE=accounts
```

Automatic certificate and profile updates are disabled. Enable them only when
required with `IOS_ALLOW_PROVISIONING_UPDATES=true`. The local machine must
have an Apple Distribution certificate and App Store provisioning profile for
`saien.someday`.

iOS Simulator tests remain unsigned CI work. Physical-device and App Store
packaging require maintainer-held signing assets and therefore run outside CI.

## Combined mobile test tracks

After verifying and committing the version change, prepare and publish Android
to Google Play internal testing and iOS to TestFlight with:

```bash
make publish-tracks
```

The terminal dashboard prepares and verifies both signed artifacts, asks for
one confirmation, and then uploads. It leaves versions, commits, pushes, and
production tracks untouched. Complete logs remain under
`build/release/test-tracks/`.

Stop before upload with preparation-only mode:

```bash
make publish-tracks MOBILE_RELEASE_ARGS="--prepare-only"
```

Non-interactive environments must confirm explicitly and use line-oriented
logs:

```bash
make publish-tracks MOBILE_RELEASE_ARGS="--yes --no-tui"
```

If one store accepts its artifact and the other fails, the command exits
unsuccessfully, preserves logs and artifacts, and prints the platform-specific
retry command. The individual Android and iOS targets remain available for
recovery. `make test-publish-tracks` verifies the coordinator without contacting
either store.

## Server compatibility

Client and server versions are independent release lines. Client tags use
`vX.Y.Z`; server tags use `server-vX.Y.Z`. Record protocol compatibility in
release notes when either side changes its minimum supported counterpart.
Matching version numbers do not indicate compatibility.
