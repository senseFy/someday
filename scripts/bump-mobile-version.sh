#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_BUILD_FILE="$ROOT_DIR/app/android/build.gradle.kts"
IOS_PROJECT_FILE="$ROOT_DIR/iosApp/Someday.xcodeproj/project.pbxproj"
DESKTOP_BUILD_FILE="$ROOT_DIR/app/desktop/build.gradle.kts"

PLATFORM=""
IOS_BUILD_NUMBER=""
IOS_MARKETING_VERSION=""
ANDROID_VERSION_CODE=""
ANDROID_VERSION_NAME=""
VERSION_NAME=""
RELEASE_BUMP=0
DRY_RUN=0
NO_COMMIT=0

usage() {
  cat <<'EOF'
Bump Someday app versions and create an independent commit.

Default behavior:
  - iOS: increment CURRENT_PROJECT_VERSION.
  - Android: increment versionCode.
  - Marketing versions are unchanged unless explicitly provided.
  - The working tree must be clean before bumping (unless --no-commit).

Release behavior:
  - --release requires --platform both.
  - Android versionName, iOS MARKETING_VERSION, and Desktop packageVersion are set
    to the same version.
  - Without --version-name, short versions like 1.0 are normalized to 1.0.0, then the
    shared version becomes max(iOS, Android, Desktop) with patch + 1.
  - Android versionCode and iOS CURRENT_PROJECT_VERSION are also incremented.
  - Pass --version-name to set all app platforms explicitly.

Usage:
  ./scripts/bump-mobile-version.sh --platform ios
  ./scripts/bump-mobile-version.sh --platform android
  ./scripts/bump-mobile-version.sh --platform both
  ./scripts/bump-mobile-version.sh --platform both --release

Options:
  --platform <ios|android|both>      Required unless using --ios or --android.
  --ios                              Alias for --platform ios.
  --android                          Alias for --platform android.
  --both                             Alias for --platform both.
  --release                          Bump the shared Android/iOS/Desktop release version.
  --version-name <version>           Set the shared release version for --release.
  --ios-build-number <number>        Set iOS CURRENT_PROJECT_VERSION.
  --ios-marketing-version <version>  Set iOS MARKETING_VERSION.
  --android-version-code <number>    Set Android versionCode.
  --android-version-name <version>   Set Android versionName.
  --no-commit                        Edit files without creating a git commit.
  --dry-run                          Print the next versions without editing or committing.

Examples:
  ./scripts/bump-mobile-version.sh --ios
  ./scripts/bump-mobile-version.sh --android
  ./scripts/bump-mobile-version.sh --both
  ./scripts/bump-mobile-version.sh --ios --ios-build-number 2
  ./scripts/bump-mobile-version.sh --both --release --version-name 1.0.5
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform)
      PLATFORM="${2:-}"
      shift 2
      ;;
    --ios)
      PLATFORM="ios"
      shift 1
      ;;
    --android)
      PLATFORM="android"
      shift 1
      ;;
    --both|--ios-android|--android-ios)
      PLATFORM="both"
      shift 1
      ;;
    --release)
      RELEASE_BUMP=1
      shift 1
      ;;
    --version-name)
      VERSION_NAME="${2:-}"
      shift 2
      ;;
    --ios-build-number)
      IOS_BUILD_NUMBER="${2:-}"
      shift 2
      ;;
    --ios-marketing-version)
      IOS_MARKETING_VERSION="${2:-}"
      shift 2
      ;;
    --android-version-code)
      ANDROID_VERSION_CODE="${2:-}"
      shift 2
      ;;
    --android-version-name)
      ANDROID_VERSION_NAME="${2:-}"
      shift 2
      ;;
    --no-commit)
      NO_COMMIT=1
      shift 1
      ;;
    --dry-run)
      DRY_RUN=1
      shift 1
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 2
      ;;
  esac
done

case "$PLATFORM" in
  ios|android|both) ;;
  "")
    echo "Missing platform. Pass --platform ios, --platform android, or --platform both." >&2
    exit 1
    ;;
  *)
    echo "Unsupported platform: $PLATFORM" >&2
    exit 1
    ;;
esac

if [[ "$RELEASE_BUMP" == 1 && "$PLATFORM" != "both" ]]; then
  echo "--release requires --platform both." >&2
  exit 1
fi

if [[ "$RELEASE_BUMP" == 1 && ( -n "$IOS_MARKETING_VERSION" || -n "$ANDROID_VERSION_NAME" ) ]]; then
  echo "--release keeps Android, iOS, and Desktop visible versions aligned. Use --version-name instead of platform-specific version options." >&2
  exit 1
fi

if [[ "$RELEASE_BUMP" != 1 && -n "$VERSION_NAME" ]]; then
  echo "--version-name requires --release." >&2
  exit 1
fi

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

section() {
  printf '\n%s %s\n' "$1" "$2"
}

detail() {
  printf '   %-10s %s\n' "$1:" "$2"
}

success() {
  printf '✅ %s\n' "$1"
}

validate_positive_integer() {
  local label="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[0-9]+$ || "$value" =~ ^0+$ ]]; then
    die "$label must be a positive integer: $value"
  fi
}

validate_version_name() {
  local label="$1"
  local value="$2"
  if [[ -z "$value" || "$value" =~ [[:space:]] ]]; then
    die "$label must be non-empty and must not contain whitespace: $value"
  fi
}

validate_release_version_name() {
  local label="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]]; then
    die "$label must use numeric major, major.minor, or major.minor.patch format, such as 1, 1.0, or 1.0.1: $value"
  fi
}

normalize_release_version_name() {
  local value="$1"
  validate_release_version_name "Release version" "$value"

  local major minor patch
  IFS='.' read -r major minor patch <<EOF
$value
EOF
  minor="${minor:-0}"
  patch="${patch:-0}"
  printf '%s.%s.%s\n' "$((10#$major))" "$((10#$minor))" "$((10#$patch))"
}

bump_patch_version() {
  local version
  version="$(normalize_release_version_name "$1")"

  local major="${version%%.*}"
  local rest="${version#*.}"
  local minor="${rest%%.*}"
  local patch="${rest#*.}"

  printf '%s.%s.%s\n' "$((10#$major))" "$((10#$minor))" "$((10#$patch + 1))"
}

compare_release_versions() {
  local left right
  left="$(normalize_release_version_name "$1")"
  right="$(normalize_release_version_name "$2")"

  local left_major="${left%%.*}"
  local left_rest="${left#*.}"
  local left_minor="${left_rest%%.*}"
  local left_patch="${left_rest#*.}"
  local right_major="${right%%.*}"
  local right_rest="${right#*.}"
  local right_minor="${right_rest%%.*}"
  local right_patch="${right_rest#*.}"

  if ((10#$left_major > 10#$right_major)); then
    printf '1\n'
  elif ((10#$left_major < 10#$right_major)); then
    printf -- '-1\n'
  elif ((10#$left_minor > 10#$right_minor)); then
    printf '1\n'
  elif ((10#$left_minor < 10#$right_minor)); then
    printf -- '-1\n'
  elif ((10#$left_patch > 10#$right_patch)); then
    printf '1\n'
  elif ((10#$left_patch < 10#$right_patch)); then
    printf -- '-1\n'
  else
    printf '0\n'
  fi
}

max_release_version() {
  local left="$1"
  local right="$2"
  if [[ "$(compare_release_versions "$left" "$right")" == "-1" ]]; then
    normalize_release_version_name "$right"
  else
    normalize_release_version_name "$left"
  fi
}

current_ios_build_number() {
  sed -n 's/.*CURRENT_PROJECT_VERSION = \([^;]*\);.*/\1/p' "$IOS_PROJECT_FILE" | head -1
}

current_ios_marketing_version() {
  sed -n 's/.*MARKETING_VERSION = \([^;]*\);.*/\1/p' "$IOS_PROJECT_FILE" | head -1
}

current_android_version_code() {
  sed -n 's/.*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9_]*\).*/\1/p' "$ANDROID_BUILD_FILE" | head -1
}

current_android_version_name() {
  sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$ANDROID_BUILD_FILE" | head -1
}

current_desktop_version() {
  sed -n 's/.*packageVersion[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$DESKTOP_BUILD_FILE" | head -1
}

require_clean_worktree() {
  local status_line path
  while IFS= read -r status_line; do
    [[ -n "$status_line" ]] || continue
    path="${status_line:3}"
    case "$path" in
      iosApp/Someday.xcodeproj/project.pbxproj|app/android/build.gradle.kts|app/desktop/build.gradle.kts) ;;
      *)
        die "Working tree has unrelated changes ($path). Commit/stash them first, or pass --no-commit."
        ;;
    esac
  done < <(git -C "$ROOT_DIR" status --porcelain --untracked-files=no)
}

should_bump_ios() {
  [[ "$PLATFORM" == "ios" || "$PLATFORM" == "both" ]]
}

should_bump_android() {
  [[ "$PLATFORM" == "android" || "$PLATFORM" == "both" ]]
}

apply_ios_versions() {
  local build_number="$1"
  local marketing_version="$2"
  python3 - "$IOS_PROJECT_FILE" "$build_number" "$marketing_version" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
build_number = sys.argv[2]
marketing_version = sys.argv[3]
text = path.read_text()

text, build_count = re.subn(
    r"(CURRENT_PROJECT_VERSION = )[^;]+;",
    rf"\g<1>{build_number};",
    text,
)
text, version_count = re.subn(
    r"(MARKETING_VERSION = )[^;]+;",
    rf"\g<1>{marketing_version};",
    text,
)

if build_count == 0:
    raise SystemExit("No CURRENT_PROJECT_VERSION assignments found")
if version_count == 0:
    raise SystemExit("No MARKETING_VERSION assignments found")

path.write_text(text)
print(f"Updated iOS CURRENT_PROJECT_VERSION in {build_count} place(s)")
print(f"Updated iOS MARKETING_VERSION in {version_count} place(s)")
PY
}

apply_android_versions() {
  local version_code="$1"
  local version_name="$2"
  python3 - "$ANDROID_BUILD_FILE" "$version_code" "$version_name" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
version_code = sys.argv[2]
version_name = sys.argv[3]
text = path.read_text()

text, code_count = re.subn(
    r"(versionCode\s*=\s*)[0-9_]+",
    rf"\g<1>{version_code}",
    text,
    count=1,
)
text, name_count = re.subn(
    r'(versionName\s*=\s*")[^"]+(")',
    rf"\g<1>{version_name}\2",
    text,
    count=1,
)

if code_count == 0:
    raise SystemExit("No versionCode assignment found")
if name_count == 0:
    raise SystemExit("No versionName assignment found")

path.write_text(text)
print("Updated Android versionCode")
print("Updated Android versionName")
PY
}

apply_desktop_version() {
  local version_name="$1"
  python3 - "$DESKTOP_BUILD_FILE" "$version_name" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
version_name = sys.argv[2]
text = path.read_text()

text, version_count = re.subn(
    r'(packageVersion\s*=\s*")[^"]+(")',
    rf"\g<1>{version_name}\2",
    text,
    count=1,
)

if version_count == 0:
    raise SystemExit("No Desktop packageVersion assignment found")

path.write_text(text)
print("Updated Desktop packageVersion")
PY
}

[[ -f "$IOS_PROJECT_FILE" ]] || die "Xcode project not found: $IOS_PROJECT_FILE"
[[ -f "$ANDROID_BUILD_FILE" ]] || die "Android build file not found: $ANDROID_BUILD_FILE"
[[ -f "$DESKTOP_BUILD_FILE" ]] || die "Desktop build file not found: $DESKTOP_BUILD_FILE"

ios_current_build="$(current_ios_build_number)"
ios_current_version="$(current_ios_marketing_version)"
android_current_code="$(current_android_version_code)"
android_current_name="$(current_android_version_name)"
desktop_current_version="$(current_desktop_version)"

ios_next_build=""
ios_next_version=""
android_next_code=""
android_next_name=""
desktop_next_version=""
release_next_version=""

if [[ "$RELEASE_BUMP" == 1 ]]; then
  if [[ -z "$ios_current_version" || -z "$android_current_name" || -z "$desktop_current_version" ]]; then
    die "Could not read current app release versions."
  fi

  local_ios_normalized=""
  local_android_normalized=""
  local_desktop_normalized=""
  release_align_note=""
  local_ios_normalized="$(normalize_release_version_name "$ios_current_version")"
  local_android_normalized="$(normalize_release_version_name "$android_current_name")"
  local_desktop_normalized="$(normalize_release_version_name "$desktop_current_version")"

  if [[ -n "$VERSION_NAME" ]]; then
    release_next_version="$(normalize_release_version_name "$VERSION_NAME")"
  else
    highest_current_version="$(max_release_version "$local_ios_normalized" "$local_android_normalized")"
    highest_current_version="$(max_release_version "$highest_current_version" "$local_desktop_normalized")"
    release_next_version="$(bump_patch_version "$highest_current_version")"
    if [[ "$local_ios_normalized" != "$local_android_normalized" || "$local_ios_normalized" != "$local_desktop_normalized" ]]; then
      release_align_note="normalized iOS $ios_current_version -> $local_ios_normalized, Android $android_current_name -> $local_android_normalized, Desktop $desktop_current_version -> $local_desktop_normalized; using max then patch + 1"
    fi
  fi

  if [[ "$(compare_release_versions "$release_next_version" "$local_ios_normalized")" == "-1" ]]; then
    die "Release version ($release_next_version) must not be lower than current iOS ($ios_current_version / $local_ios_normalized) version."
  fi
  if [[ "$(compare_release_versions "$release_next_version" "$local_android_normalized")" == "-1" ]]; then
    die "Release version ($release_next_version) must not be lower than current Android ($android_current_name / $local_android_normalized) version."
  fi
  if [[ "$(compare_release_versions "$release_next_version" "$local_desktop_normalized")" == "-1" ]]; then
    die "Release version ($release_next_version) must not be lower than current Desktop ($desktop_current_version / $local_desktop_normalized) version."
  fi
  if [[ "$release_next_version" == "$local_ios_normalized" && "$release_next_version" == "$local_android_normalized" && "$release_next_version" == "$local_desktop_normalized" ]]; then
    die "Release version is already $release_next_version on all app platforms. Use a build/code bump instead."
  fi

  desktop_next_version="$release_next_version"
fi

if should_bump_ios; then
  if [[ -z "$ios_current_build" || -z "$ios_current_version" ]]; then
    die "Could not read current iOS version from $IOS_PROJECT_FILE"
  fi
  validate_positive_integer "Current iOS build number" "$ios_current_build"
  ios_next_build="${IOS_BUILD_NUMBER:-$((10#$ios_current_build + 1))}"
  ios_next_version="${release_next_version:-${IOS_MARKETING_VERSION:-$ios_current_version}}"
  validate_positive_integer "iOS build number" "$ios_next_build"
  validate_version_name "iOS marketing version" "$ios_next_version"
fi

if should_bump_android; then
  if [[ -z "$android_current_code" || -z "$android_current_name" ]]; then
    die "Could not read current Android version from $ANDROID_BUILD_FILE"
  fi
  android_current_code_digits="${android_current_code//_/}"
  android_requested_code_digits="${ANDROID_VERSION_CODE//_/}"
  validate_positive_integer "Current Android versionCode" "$android_current_code_digits"
  android_next_code="${android_requested_code_digits:-$((10#$android_current_code_digits + 1))}"
  android_next_name="${release_next_version:-${ANDROID_VERSION_NAME:-$android_current_name}}"
  validate_positive_integer "Android versionCode" "$android_next_code"
  validate_version_name "Android versionName" "$android_next_name"
fi

section "🔖" "Version bump"
if [[ -n "${release_align_note:-}" ]]; then
  detail "Note" "$release_align_note"
fi
if should_bump_ios; then
  detail "iOS" "$ios_current_version ($ios_current_build) -> $ios_next_version ($ios_next_build)"
fi
if should_bump_android; then
  detail "Android" "$android_current_name ($android_current_code) -> $android_next_name ($android_next_code)"
fi
if [[ "$RELEASE_BUMP" == 1 ]]; then
  detail "Desktop" "$desktop_current_version -> $desktop_next_version"
fi

if [[ "$DRY_RUN" == 1 ]]; then
  detail "Mode" "dry run, no files changed"
  exit 0
fi

if [[ "$NO_COMMIT" == 0 ]]; then
  command -v git >/dev/null 2>&1 || die "git is required to create the version bump commit"
  [[ -d "$ROOT_DIR/.git" ]] || die "Not a git repository: $ROOT_DIR"
  require_clean_worktree
fi

changed_files=()
commit_subject=""

if should_bump_ios; then
  apply_ios_versions "$ios_next_build" "$ios_next_version"
  changed_files+=("iosApp/Someday.xcodeproj/project.pbxproj")
  commit_subject="Bump iOS version to $ios_next_version ($ios_next_build)"
fi

if should_bump_android; then
  apply_android_versions "$android_next_code" "$android_next_name"
  changed_files+=("app/android/build.gradle.kts")
  commit_subject="Bump Android version to $android_next_name ($android_next_code)"
fi

if [[ "$RELEASE_BUMP" == 1 ]]; then
  apply_desktop_version "$desktop_next_version"
  changed_files+=("app/desktop/build.gradle.kts")
  commit_subject="Bump app version to $release_next_version"
elif [[ "$PLATFORM" == "both" ]]; then
  commit_subject="Bump mobile versions"
fi

if [[ "$NO_COMMIT" == 1 ]]; then
  detail "Mode" "no commit"
  success "Version files updated"
  exit 0
fi

git -C "$ROOT_DIR" add "${changed_files[@]}"
if git -C "$ROOT_DIR" diff --cached --quiet; then
  detail "Result" "no version changes to commit"
  exit 0
fi

git -C "$ROOT_DIR" commit -m "$commit_subject"
success "Version bump committed: $commit_subject"
