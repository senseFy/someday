#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
cd "$ROOT_DIR"

if [[ ! -x "$GRADLEW" ]]; then
  echo "Gradle wrapper is not executable: $GRADLEW" >&2
  exit 1
fi

case "${SDK_NAME:-}" in
  iphoneos*)
    kmp_target="IosArm64"
    kmp_dir_target="iosArm64"
    ;;
  iphonesimulator*)
    case "$(uname -m)" in
      arm64)
        kmp_target="IosSimulatorArm64"
        kmp_dir_target="iosSimulatorArm64"
        ;;
      x86_64)
        echo "Intel iOS Simulator hosts are not supported because Backdrop 2.0.0 does not publish an iosX64 artifact." >&2
        exit 1
        ;;
      *)
        echo "Unsupported iOS simulator host architecture: $(uname -m)" >&2
        exit 1
        ;;
    esac
    ;;
  *)
    echo "Unsupported iOS SDK_NAME: ${SDK_NAME:-unknown}" >&2
    exit 1
    ;;
esac

case "${CONFIGURATION:-Debug}" in
  Release)
    kmp_build_type="Release"
    kmp_variant="release"
    developer_options="false"
    release_activation_args=(
      -Psomeday.systemV2ReleaseEnabled=true
      -Psomeday.systemV2DevelopmentEnabled=false
    )
    ;;
  *)
    kmp_build_type="Debug"
    kmp_variant="debug"
    developer_options="true"
    release_activation_args=()
    ;;
esac

gradle_max_workers="${SOMEDAY_IOS_GRADLE_MAX_WORKERS:-2}"
case "$gradle_max_workers" in
  "" | 0 | *[!0-9]*)
    echo "SOMEDAY_IOS_GRADLE_MAX_WORKERS must be a positive integer: $gradle_max_workers" >&2
    exit 1
    ;;
esac

framework_dir="app/ios/build/bin/${kmp_dir_target}/${kmp_variant}Framework/SomedayIos.framework"
framework_binary="${framework_dir}/SomedayIos"
resources_src="app/ios/build/processedResources/${kmp_dir_target}/main/composeResources"
resources_dst="${framework_dir}/composeResources"
expected_resources="${resources_dst}/saien.someday.ui.resources"

verify_framework() {
  if [[ ! -f "$framework_binary" ]]; then
    echo "SomedayIos framework not found at $framework_binary." >&2
    echo "Prebuild the framework and Compose resources before asking Xcode to reuse them." >&2
    exit 1
  fi
}

sync_compose_resources() {
  if [[ ! -d "$resources_src" ]]; then
    echo "Compose resources not found at $resources_src" >&2
    exit 1
  fi
  if [[ ! -d "$framework_dir" ]]; then
    echo "SomedayIos framework directory not found at $framework_dir" >&2
    exit 1
  fi

  rm -rf "$resources_dst"
  mkdir -p "$resources_dst"
  cp -R "$resources_src"/. "$resources_dst"/

  if [[ ! -d "$expected_resources" ]]; then
    echo "Missing expected Compose UI resources under $expected_resources" >&2
    exit 1
  fi
  echo "Synced Compose resources into $resources_dst"
}

if [[ "${SOMEDAY_SKIP_FRAMEWORK_BUILD:-}" == "1" ]]; then
  verify_framework
  if [[ ! -d "$expected_resources" ]]; then
    echo "Prebuilt Compose resources not found at $expected_resources." >&2
    echo "The release prebuild must copy resources into the framework before Xcode starts." >&2
    exit 1
  fi
  echo "Using prebuilt SomedayIos framework and Compose resources at $framework_dir"
  exit 0
fi

"$GRADLEW" \
  ":app:ios:link${kmp_build_type}Framework${kmp_target}" \
  ":app:ios:${kmp_dir_target}ProcessResources" \
  -Psomeday.ios.developerOptions="$developer_options" \
  "${release_activation_args[@]}" \
  --dependency-verification=strict \
  --max-workers="$gradle_max_workers" \
  --stacktrace

verify_framework
sync_compose_resources
