#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PROJECT="$ROOT_DIR/iosApp/Someday.xcodeproj"
SCHEME="Someday"
CONFIGURATION="Release"
DERIVED_DATA="$ROOT_DIR/iosApp/build/XcodeDerivedData"
ENTITLEMENTS_PATH="$ROOT_DIR/iosApp/Someday/Someday.entitlements"
GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
FRAMEWORK_PATH="$ROOT_DIR/app/ios/build/bin/iosArm64/releaseFramework/SomedayIos.framework"
IOS_GRADLE_MAX_WORKERS="${IOS_GRADLE_MAX_WORKERS:-2}"

TEAM_ID="${IOS_TEAM_ID:-}"
ARCHIVE_PATH="${IOS_ARCHIVE_PATH:-$ROOT_DIR/iosApp/build/Someday-Release.xcarchive}"
EXPORT_PATH="${IOS_EXPORT_PATH:-$ROOT_DIR/iosApp/build/AppStoreExport}"
EXPORT_PATH_PROVIDED=0
if [[ -n "${IOS_EXPORT_PATH:-}" ]]; then
  EXPORT_PATH_PROVIDED=1
fi
UPLOAD_EXPORT_PATH="${IOS_UPLOAD_EXPORT_PATH:-$ROOT_DIR/iosApp/build/AppStoreUpload}"
BUILD_NUMBER="${IOS_BUILD_NUMBER:-}"
MARKETING_VERSION="${IOS_MARKETING_VERSION:-}"
PROVISIONING_PROFILE_SPECIFIER="${IOS_PROVISIONING_PROFILE_SPECIFIER:-}"
EXPORT_BUNDLE_ID="${IOS_EXPORT_BUNDLE_ID:-}"
SIGNING_CERTIFICATE="${IOS_SIGNING_CERTIFICATE:-Apple Distribution}"
SIGNING_CERTIFICATE_PROVIDED=0
if [[ -n "${IOS_SIGNING_CERTIFICATE:-}" ]]; then
  SIGNING_CERTIFICATE_PROVIDED=1
fi
RESOLVED_PROVISIONING_PROFILE_SPECIFIER=""
RESOLVED_PROVISIONING_PROFILE_NAME=""
RESOLVED_SIGNING_CERTIFICATE=""
RESOLVED_EXPORT_BUNDLE_ID=""
MATCHED_SIGNING_CERTIFICATE=""
INSTALLED_CODE_SIGNING_IDENTITIES=""
UPLOAD_AUTH_MODE="${IOS_UPLOAD_AUTH_MODE:-api-key}"

AUTH_KEY_SOURCE_PATH="${APP_STORE_CONNECT_API_KEY_PATH:-}"
AUTH_KEY_PATH=""
AUTH_KEY_ID="${APP_STORE_CONNECT_API_KEY_ID:-}"
AUTH_KEY_ISSUER_ID="${APP_STORE_CONNECT_API_ISSUER_ID:-}"

DO_ARCHIVE=1
DO_EXPORT=1
DESTINATION="export"
ALLOW_PROVISIONING_UPDATES="${IOS_ALLOW_PROVISIONING_UPDATES:-false}"
CLEAN_CUSTOM_PATHS=0
VERBOSE=0
PRIVATE_TEMP_DIR=""

usage() {
  cat <<'EOF'
Archive and export the Someday iOS app for App Store Connect / TestFlight.

Default behavior:
  - Prebuild the Release SomedayIos.framework with developer options disabled.
  - Build a signed Release archive.
  - Export a local App Store Connect IPA.
  - Print redacted next-step guidance after local export.
  - In interactive terminals, offer direct upload before export so the archive is exported once.

Usage:
  ./scripts/release-ios.sh --team <apple-team-id>
  ./scripts/release-ios.sh --team <apple-team-id> --archive-only
  ./scripts/release-ios.sh --team <apple-team-id> --export-only
  ./scripts/release-ios.sh --team <apple-team-id> --upload

Common options:
  --team <id>                     Apple Developer Team ID. Also IOS_TEAM_ID.
  --archive-path <path>           Archive output path. Also IOS_ARCHIVE_PATH.
  --export-path <path>            Export output path. Also IOS_EXPORT_PATH.
  --build-number <number>         Override CURRENT_PROJECT_VERSION. Also IOS_BUILD_NUMBER.
  --version <version>             Override MARKETING_VERSION. Also IOS_MARKETING_VERSION.
  --provisioning-profile <name>   Export with a specific App Store provisioning profile.
                                  Also IOS_PROVISIONING_PROFILE_SPECIFIER.
  --bundle-id <id>                Bundle ID for manual export profile mapping.
                                  Defaults to the archive bundle ID. Also IOS_EXPORT_BUNDLE_ID.
  --signing-certificate <name>    Signing certificate selector for manual export.
                                  Defaults to "Apple Distribution". Also IOS_SIGNING_CERTIFICATE.
                                  The script resolves the selector to the exact SHA-1 identity
                                  included in a compatible installed App Store profile.
  --archive-only                  Stop after creating the .xcarchive.
  --export-only                   Export an existing archive without archiving.
  --upload                        Upload to App Store Connect instead of exporting a local IPA.
  --upload-auth-mode <mode>       Upload auth mode: api-key or accounts.
                                  Defaults to api-key. Also IOS_UPLOAD_AUTH_MODE.
  --allow-provisioning-updates    Let Xcode update certificates/profiles.
                                  Disabled by default.
  --no-allow-provisioning-updates Disable Xcode certificate/profile updates.
  --clean                         Allow deleting custom archive/export paths before writing.
  --verbose                       Print full redacted xcodebuild output.

Environment:
  IOS_GRADLE_MAX_WORKERS=<count>  Gradle workers used by the iOS prebuild. Defaults to 2.
  IOS_ALLOW_PROVISIONING_UPDATES  Set to true only when Xcode may update signing assets.
  APP_STORE_CONNECT_API_KEY_PATH  API private key outside the repository, mode 0600 or stricter.
  APP_STORE_CONNECT_API_KEY_ID    API key identifier.
  APP_STORE_CONNECT_API_ISSUER_ID API issuer identifier.

Examples:
  export IOS_TEAM_ID=YOUR_TEAM_ID
  ./scripts/release-ios.sh
  ./scripts/release-ios.sh --upload
  make ios-release
  make ios-upload-testflight
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --team)
      TEAM_ID="${2:-}"
      shift 2
      ;;
    --archive-path)
      ARCHIVE_PATH="${2:-}"
      shift 2
      ;;
    --export-path)
      EXPORT_PATH="${2:-}"
      EXPORT_PATH_PROVIDED=1
      shift 2
      ;;
    --build-number)
      BUILD_NUMBER="${2:-}"
      shift 2
      ;;
    --version)
      MARKETING_VERSION="${2:-}"
      shift 2
      ;;
    --provisioning-profile|--profile)
      PROVISIONING_PROFILE_SPECIFIER="${2:-}"
      shift 2
      ;;
    --bundle-id)
      EXPORT_BUNDLE_ID="${2:-}"
      shift 2
      ;;
    --signing-certificate)
      SIGNING_CERTIFICATE="${2:-}"
      SIGNING_CERTIFICATE_PROVIDED=1
      shift 2
      ;;
    --archive-only)
      DO_ARCHIVE=1
      DO_EXPORT=0
      shift 1
      ;;
    --export-only)
      DO_ARCHIVE=0
      DO_EXPORT=1
      shift 1
      ;;
    --upload)
      DESTINATION="upload"
      DO_EXPORT=1
      shift 1
      ;;
    --upload-auth-mode)
      UPLOAD_AUTH_MODE="${2:-}"
      shift 2
      ;;
    --allow-provisioning-updates)
      ALLOW_PROVISIONING_UPDATES=true
      shift 1
      ;;
    --no-allow-provisioning-updates)
      ALLOW_PROVISIONING_UPDATES=false
      shift 1
      ;;
    --clean)
      CLEAN_CUSTOM_PATHS=1
      shift 1
      ;;
    --verbose)
      VERBOSE=1
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

if [[ "${BUMP_VERSION+x}" == x || "${IOS_BUMP_VERSION+x}" == x ]]; then
  echo "BUMP_VERSION is no longer supported. Run make bump-ios-version before releasing." >&2
  exit 2
fi
if [[ "${UPLOAD+x}" == x || "${IOS_UPLOAD+x}" == x ]]; then
  echo "UPLOAD is no longer supported. Pass --upload or use an explicit Make upload target." >&2
  exit 2
fi

if [[ -z "$TEAM_ID" ]]; then
  echo "Missing Apple Developer Team ID. Export IOS_TEAM_ID or pass --team." >&2
  exit 1
fi
if [[ ! "$TEAM_ID" =~ ^[A-Z0-9]{10}$ ]]; then
  echo "Apple Developer Team ID must be 10 uppercase letters or digits." >&2
  exit 1
fi

if [[ ! "$IOS_GRADLE_MAX_WORKERS" =~ ^[1-9][0-9]*$ ]]; then
  echo "IOS_GRADLE_MAX_WORKERS must be a positive integer." >&2
  exit 1
fi

case "$ALLOW_PROVISIONING_UPDATES" in
  yes|true|1) ALLOW_PROVISIONING_UPDATES=1 ;;
  no|false|0|"") ALLOW_PROVISIONING_UPDATES=0 ;;
  *)
    echo "IOS_ALLOW_PROVISIONING_UPDATES must be true or false." >&2
    exit 1
    ;;
esac

if [[ -n "$AUTH_KEY_SOURCE_PATH$AUTH_KEY_ID$AUTH_KEY_ISSUER_ID" ]]; then
  if [[ -z "$AUTH_KEY_SOURCE_PATH" || -z "$AUTH_KEY_ID" || -z "$AUTH_KEY_ISSUER_ID" ]]; then
    echo "App Store Connect API auth requires key path, key ID, and issuer ID." >&2
    exit 1
  fi
  if [[ ! "$AUTH_KEY_ID" =~ ^[A-Z0-9]{10}$ ]]; then
    echo "App Store Connect API key ID must be 10 uppercase letters or digits." >&2
    exit 1
  fi
  if [[ ! "$AUTH_KEY_ISSUER_ID" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]]; then
    echo "App Store Connect API issuer ID must be a UUID." >&2
    exit 1
  fi
fi

case "$UPLOAD_AUTH_MODE" in
  api-key|accounts) ;;
  *)
    echo "IOS_UPLOAD_AUTH_MODE must be api-key or accounts." >&2
    exit 1
    ;;
esac

if [[ "$UPLOAD_AUTH_MODE" == "accounts" && -n "$AUTH_KEY_SOURCE_PATH$AUTH_KEY_ID$AUTH_KEY_ISSUER_ID" ]]; then
  echo "Do not configure App Store Connect API key variables in accounts mode." >&2
  exit 1
fi

if [[ "$DO_ARCHIVE" == 0 && ! -d "$ARCHIVE_PATH" ]]; then
  echo "Configured iOS archive was not found." >&2
  exit 1
fi

if [[ "$DESTINATION" == "upload" && "$EXPORT_PATH_PROVIDED" == 0 ]]; then
  EXPORT_PATH="$UPLOAD_EXPORT_PATH"
fi

absolute_path() {
  local path="$1"
  case "$path" in
    /*) printf '%s\n' "$path" ;;
    *) printf '%s\n' "$(pwd -P)/$path" ;;
  esac
}

display_path() {
  local path="$1"
  case "$path" in
    "$ROOT_DIR"/*) printf '%s\n' "${path#"$ROOT_DIR"/}" ;;
    *) printf '<custom path>\n' ;;
  esac
}

cleanup_private_temp() {
  if [[ -n "$PRIVATE_TEMP_DIR" && -d "$PRIVATE_TEMP_DIR" ]]; then
    rm -rf -- "$PRIVATE_TEMP_DIR"
  fi
}

ARCHIVE_PATH="$(absolute_path "$ARCHIVE_PATH")"
EXPORT_PATH="$(absolute_path "$EXPORT_PATH")"
UPLOAD_EXPORT_PATH="$(absolute_path "$UPLOAD_EXPORT_PATH")"
if [[ -n "$AUTH_KEY_SOURCE_PATH" ]]; then
  AUTH_KEY_SOURCE_PATH="$(absolute_path "$AUTH_KEY_SOURCE_PATH")"
  if [[ ! -f "$AUTH_KEY_SOURCE_PATH" ]]; then
    echo "Configured App Store Connect API key path does not point to a file." >&2
    exit 1
  fi
  if [[ -L "$AUTH_KEY_SOURCE_PATH" ]]; then
    echo "App Store Connect API key must be a regular, non-symlink file." >&2
    exit 1
  fi
  auth_key_directory="$(dirname "$AUTH_KEY_SOURCE_PATH")"
  auth_key_filename="$(basename "$AUTH_KEY_SOURCE_PATH")"
  if ! auth_key_physical_directory="$(cd "$auth_key_directory" 2>/dev/null && pwd -P)"; then
    echo "Unable to resolve the configured App Store Connect API key path." >&2
    exit 1
  fi
  AUTH_KEY_SOURCE_PATH="$auth_key_physical_directory/$auth_key_filename"
  case "$AUTH_KEY_SOURCE_PATH" in
    "$ROOT_DIR"/*)
      echo "App Store Connect API key must be stored outside the repository." >&2
      exit 1
      ;;
  esac
fi

[[ "$(uname -s)" == "Darwin" ]] || {
  echo "iOS release packaging must run on macOS." >&2
  exit 1
}
command -v xcodebuild >/dev/null 2>&1 || {
  echo "Missing required command: xcodebuild" >&2
  exit 1
}
[[ -x "$GRADLEW" ]] || {
  echo "Configured Gradle wrapper is not executable." >&2
  exit 1
}
[[ -d "$PROJECT" ]] || {
  echo "Someday Xcode project was not found." >&2
  exit 1
}

if [[ -n "$AUTH_KEY_SOURCE_PATH" ]]; then
  auth_key_mode="$(stat -f '%Lp' "$AUTH_KEY_SOURCE_PATH" 2>/dev/null || true)"
  if [[ ! "$auth_key_mode" =~ ^[0-7]{3,4}$ ]] ||
    (( (8#$auth_key_mode & 8#077) != 0 )); then
    echo "App Store Connect API key permissions must be 0600 or stricter." >&2
    exit 1
  fi
fi

PRIVATE_TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/someday-ios-release.XXXXXX")"
trap cleanup_private_temp EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ -n "$AUTH_KEY_SOURCE_PATH" ]]; then
  AUTH_KEY_PATH="$PRIVATE_TEMP_DIR/AuthKey.p8"
  if ! install -m 600 "$AUTH_KEY_SOURCE_PATH" "$AUTH_KEY_PATH" 2>/dev/null; then
    echo "Unable to stage the configured App Store Connect API key." >&2
    exit 1
  fi
fi

xcode_quiet_arg=(-quiet)
if [[ "$VERBOSE" == 1 ]]; then
  xcode_quiet_arg=()
fi

provisioning_args=()
if [[ "$ALLOW_PROVISIONING_UPDATES" == 1 ]]; then
  provisioning_args+=(-allowProvisioningUpdates)
fi
if [[ -n "$AUTH_KEY_PATH" ]]; then
  provisioning_args+=(
    -authenticationKeyPath "$AUTH_KEY_PATH"
    -authenticationKeyID "$AUTH_KEY_ID"
    -authenticationKeyIssuerID "$AUTH_KEY_ISSUER_ID"
  )
fi

archive_xcconfig="$PRIVATE_TEMP_DIR/Archive.xcconfig"
printf 'DEVELOPMENT_TEAM = %s\nCODE_SIGN_STYLE = Automatic\n' "$TEAM_ID" >"$archive_xcconfig"
build_overrides=(-xcconfig "$archive_xcconfig")
if [[ -n "$BUILD_NUMBER" ]]; then
  build_overrides+=(CURRENT_PROJECT_VERSION="$BUILD_NUMBER")
fi
if [[ -n "$MARKETING_VERSION" ]]; then
  build_overrides+=(MARKETING_VERSION="$MARKETING_VERSION")
fi

section() {
  printf '\n%s %s\n' "$1" "$2"
}

detail() {
  printf '   %-10s %s\n' "$1:" "$2"
}

success() {
  printf '✅ %s\n' "$1"
}

release_mode() {
  if [[ "$DO_ARCHIVE" == 1 && "$DESTINATION" == "upload" ]]; then
    printf 'archive + upload'
  elif [[ "$DO_ARCHIVE" == 1 && "$DO_EXPORT" == 1 ]]; then
    printf 'archive + export'
  elif [[ "$DO_ARCHIVE" == 1 ]]; then
    printf 'archive only'
  elif [[ "$DESTINATION" == "upload" ]]; then
    printf 'upload existing archive'
  else
    printf 'export existing archive'
  fi
}

print_release_summary() {
  section "🚀" "iOS release"
  detail "Mode" "$(release_mode)"
  detail "Scheme" "$SCHEME ($CONFIGURATION)"
  detail "Team" "configured"
  if [[ -n "$PROVISIONING_PROFILE_SPECIFIER" ]]; then
    detail "Profile" "configured"
  fi
  if [[ "$DO_EXPORT" == 1 && -n "$RESOLVED_SIGNING_CERTIFICATE" ]]; then
    detail "Export signing" "exact profile/certificate pair verified"
  elif [[ "$DO_EXPORT" == 1 ]]; then
    detail "Export signing" "automatic"
  else
    detail "Archive signing" "automatic"
  fi
  detail "Archive" "$(display_path "$ARCHIVE_PATH")"
  if [[ "$ALLOW_PROVISIONING_UPDATES" == 1 ]]; then
    detail "Updates" "Xcode signing updates enabled"
  else
    detail "Updates" "Xcode signing updates disabled"
  fi
}

redact_release_output() {
  local suppress_noise="${1:-0}"
  awk \
    -v suppress_noise="$suppress_noise" \
    -v root="$ROOT_DIR" \
    -v home="${HOME:-}" \
    -v private_temp="$PRIVATE_TEMP_DIR" \
    -v archive="$ARCHIVE_PATH" \
    -v export_path="$EXPORT_PATH" \
    -v upload_export_path="$UPLOAD_EXPORT_PATH" \
    -v team="$TEAM_ID" \
    -v key_id="$AUTH_KEY_ID" \
    -v issuer_id="$AUTH_KEY_ISSUER_ID" \
    -v profile="$RESOLVED_PROVISIONING_PROFILE_SPECIFIER" \
    -v profile_name="$RESOLVED_PROVISIONING_PROFILE_NAME" \
    -v certificate="$SIGNING_CERTIFICATE" \
    -v resolved_certificate="$RESOLVED_SIGNING_CERTIFICATE" '
    function replace_literal(value, needle, replacement, position) {
      if (needle == "") {
        return value
      }
      while ((position = index(value, needle)) > 0) {
        value = substr(value, 1, position - 1) replacement substr(value, position + length(needle))
      }
      return value
    }
    {
      if (suppress_noise == 1 &&
          $0 ~ /IDERunDestination: Supported platforms for the buildables in the current scheme is empty\./) {
        next
      }
      if (suppress_noise == 1 &&
          $0 ~ /IDEDistribution: -\[IDEDistributionLogging _createLoggingBundleAtPath:\]/) {
        next
      }
      if (suppress_noise == 1 && $0 == previous && $0 ~ /Progress [0-9]+%:/) {
        next
      }
      previous = $0
      line = replace_literal($0, private_temp, "<private-temp>")
      line = replace_literal(line, archive, "<archive-path>")
      line = replace_literal(line, export_path, "<export-path>")
      line = replace_literal(line, upload_export_path, "<upload-export-path>")
      line = replace_literal(line, root, "<repo>")
      line = replace_literal(line, home, "<home>")
      line = replace_literal(line, team, "<team-id>")
      line = replace_literal(line, key_id, "<key-id>")
      line = replace_literal(line, issuer_id, "<issuer-id>")
      line = replace_literal(line, profile, "<provisioning-profile>")
      line = replace_literal(line, profile_name, "<provisioning-profile>")
      line = replace_literal(line, certificate, "<signing-certificate>")
      line = replace_literal(line, resolved_certificate, "<signing-certificate>")
      print line
    }
  '
}

run_xcodebuild() {
  local suppress_noise=1
  if [[ "$VERBOSE" == 1 ]]; then
    suppress_noise=0
  fi

  set +e
  xcodebuild "$@" 2>&1 | redact_release_output "$suppress_noise"
  local status=${PIPESTATUS[0]}
  set -e
  return "$status"
}

build_someday_ios_for_archive() {
  local framework_task=":app:ios:linkReleaseFrameworkIosArm64"
  local resources_task=":app:ios:iosArm64ProcessResources"

  section "🧩" "Build SomedayIos"
  detail "Tasks" "$framework_task + $resources_task"
  detail "Developer options" "false"
  detail "Gradle workers" "$IOS_GRADLE_MAX_WORKERS"
  set +e
  SDK_NAME=iphoneos \
    CONFIGURATION=Release \
    GRADLEW="$GRADLEW" \
    SOMEDAY_IOS_GRADLE_MAX_WORKERS="$IOS_GRADLE_MAX_WORKERS" \
    "$ROOT_DIR/scripts/build-ios-framework-for-xcode.sh" 2>&1 |
    redact_release_output 0
  local status=${PIPESTATUS[0]}
  set -e
  if [[ "$status" -ne 0 ]]; then
    return "$status"
  fi

  if [[ ! -d "$FRAMEWORK_PATH" || ! -f "$FRAMEWORK_PATH/SomedayIos" ]]; then
    echo "Generated SomedayIos release framework was not found." >&2
    exit 1
  fi
  success "SomedayIos release framework ready."
}

is_generated_path() {
  local path="$1"
  case "$path" in
    "$ROOT_DIR"/iosApp/build/*) return 0 ;;
    /private/tmp/Someday-*) return 0 ;;
    /tmp/Someday-*) return 0 ;;
    *) return 1 ;;
  esac
}

prepare_output_path() {
  local path="$1"

  if [[ ! -e "$path" ]]; then
    return 0
  fi

  if is_generated_path "$path" || [[ "$CLEAN_CUSTOM_PATHS" == 1 ]]; then
    rm -rf "$path"
    return 0
  fi

  echo "Refusing to delete a custom output path without --clean." >&2
  exit 1
}

has_app_store_connect_auth() {
  [[ -n "$AUTH_KEY_PATH" && -n "$AUTH_KEY_ID" && -n "$AUTH_KEY_ISSUER_ID" ]]
}

print_upload_command() {
  section "⬆️" "Upload later"
  if has_app_store_connect_auth; then
    detail "Auth" "App Store Connect API key environment configured"
  else
    detail "Auth" "export the canonical App Store Connect API key variables"
  fi
  detail "Retry" "reuse the exported release environment"
  printf '   make ios-upload-archive\n'
}

require_upload_auth() {
  local destination="$1"

  if [[ "$UPLOAD_AUTH_MODE" == "accounts" || "$destination" != "upload" ]]; then
    return 0
  fi
  if has_app_store_connect_auth; then
    return 0
  fi

  section "🔐" "App Store Connect auth required"
  detail "Reason" "upload uses App Store Connect; local signing profiles are not enough"
  detail "Required" "APP_STORE_CONNECT_API_KEY_PATH, APP_STORE_CONNECT_API_KEY_ID, APP_STORE_CONNECT_API_ISSUER_ID"
  detail "Retry" "export those variables, or explicitly use IOS_UPLOAD_AUTH_MODE=accounts"
  local exported_ipa="$ROOT_DIR/iosApp/build/AppStoreExport/Someday.ipa"
  if [[ -f "$exported_ipa" ]]; then
    detail "IPA" "local export available"
  fi
  print_upload_command
  exit 1
}

print_testflight_link() {
  section "🔗" "TestFlight"
  detail "Open" "https://appstoreconnect.apple.com/apps"
}

xml_escape() {
  local value="$1"
  value="${value//&/&amp;}"
  value="${value//</&lt;}"
  value="${value//>/&gt;}"
  value="${value//\"/&quot;}"
  value="${value//\'/&apos;}"
  printf '%s' "$value"
}

archive_bundle_id() {
  local info_plist="$ARCHIVE_PATH/Info.plist"
  if [[ ! -f "$info_plist" ]]; then
    return 1
  fi
  /usr/libexec/PlistBuddy -c 'Print :ApplicationProperties:CFBundleIdentifier' "$info_plist" 2>/dev/null
}

normalize_sha1() {
  printf '%s' "$1" | tr -d '[:space:]:' | tr '[:lower:]' '[:upper:]'
}

certificate_sha1() {
  local cert_path="$1"
  local fingerprint

  fingerprint="$(openssl x509 -inform DER -in "$cert_path" -noout -fingerprint -sha1 2>/dev/null || true)"
  fingerprint="${fingerprint#*=}"
  fingerprint="$(normalize_sha1 "$fingerprint")"
  if [[ ! "$fingerprint" =~ ^[0-9A-F]{40}$ ]]; then
    return 1
  fi
  printf '%s\n' "$fingerprint"
}

load_installed_code_signing_identities() {
  if [[ -n "$INSTALLED_CODE_SIGNING_IDENTITIES" ]]; then
    return 0
  fi
  INSTALLED_CODE_SIGNING_IDENTITIES="$(security find-identity -v -p codesigning 2>/dev/null || true)"
}

signing_certificate_selector_matches() {
  local fingerprint="$1"
  local subject="$2"
  local normalized_selector

  normalized_selector="$(normalize_sha1 "$SIGNING_CERTIFICATE")"
  if [[ "$normalized_selector" =~ ^[0-9A-F]{40}$ ]]; then
    [[ "$fingerprint" == "$normalized_selector" ]]
    return
  fi
  [[ "$subject" == *"$SIGNING_CERTIFICATE"* ]]
}

profile_matching_signing_certificate() {
  local plist_path="$1"
  local index=0
  local cert_path
  local fingerprint
  local subject

  MATCHED_SIGNING_CERTIFICATE=""
  load_installed_code_signing_identities

  while true; do
    cert_path="$PRIVATE_TEMP_DIR/profile-cert-$index.cer"
    if ! /usr/libexec/PlistBuddy -c "Print :DeveloperCertificates:$index" "$plist_path" > "$cert_path" 2>/dev/null; then
      rm -f "$cert_path"
      break
    fi

    fingerprint="$(certificate_sha1 "$cert_path" || true)"
    subject="$(openssl x509 -inform DER -in "$cert_path" -noout -subject 2>/dev/null || true)"
    rm -f "$cert_path"
    # A certificate embedded in the profile is usable only when its private key is
    # present in a valid local code-signing identity.
    if [[ -n "$fingerprint" ]] &&
      signing_certificate_selector_matches "$fingerprint" "$subject" &&
      [[ "$INSTALLED_CODE_SIGNING_IDENTITIES" == *"$fingerprint"* ]]; then
      MATCHED_SIGNING_CERTIFICATE="$fingerprint"
      return 0
    fi

    index=$((index + 1))
  done

  return 1
}

profile_is_current() {
  local plist_path="$1"
  local expiration
  local now

  expiration="$(plutil -extract ExpirationDate raw -o - "$plist_path" 2>/dev/null || true)"
  now="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  [[ -n "$expiration" && "$expiration" > "$now" ]]
}

entitlement_value_matches() {
  local expected="$1"
  local actual="$2"

  if [[ "$actual" == "$expected" ]]; then
    return 0
  fi

  # App Store profiles commonly grant keychain groups as TEAMID.* wildcards.
  if [[ "$actual" == *\* ]]; then
    local prefix="${actual%\*}"
    if [[ -n "$prefix" && "$expected" == "$prefix"* ]]; then
      return 0
    fi
  fi

  return 1
}

plist_array_contains() {
  local plist_path="$1"
  local key_path="$2"
  local expected="$3"
  local index=0
  local value

  while value="$(/usr/libexec/PlistBuddy -c "Print :$key_path:$index" "$plist_path" 2>/dev/null)"; do
    if entitlement_value_matches "$expected" "$value"; then
      return 0
    fi
    index=$((index + 1))
  done

  return 1
}

entitlements_has_key() {
  local key="$1"
  [[ -f "$ENTITLEMENTS_PATH" ]] &&
    /usr/libexec/PlistBuddy -c "Print :$key" "$ENTITLEMENTS_PATH" >/dev/null 2>&1
}

required_entitlement_keys() {
  [[ -f "$ENTITLEMENTS_PATH" ]] || return 0
  plutil -p "$ENTITLEMENTS_PATH" | awk -F'"' '/^  "/ { print $2 }'
}

plist_value_kind() {
  local plist_path="$1"
  local key_path="$2"
  local first_line

  first_line="$(/usr/libexec/PlistBuddy -c "Print :$key_path" "$plist_path" 2>/dev/null | sed -n '1p')"
  case "$first_line" in
    "Array {"*) printf 'array\n' ;;
    "Dict {"*) printf 'dict\n' ;;
    *) printf 'scalar\n' ;;
  esac
}

expand_entitlement_value() {
  local value="$1"
  local bundle_id="$2"
  local app_identifier_prefix="$TEAM_ID."

  value="${value//\$(AppIdentifierPrefix)/$app_identifier_prefix}"
  value="${value//\$(TeamIdentifierPrefix)/$app_identifier_prefix}"
  value="${value//\$(CFBundleIdentifier)/$bundle_id}"
  value="${value//\${AppIdentifierPrefix}/$app_identifier_prefix}"
  value="${value//\${TeamIdentifierPrefix}/$app_identifier_prefix}"
  value="${value//\${CFBundleIdentifier}/$bundle_id}"
  printf '%s' "$value"
}

entitlement_value_is_distribution_managed() {
  local key="$1"
  case "$key" in
    aps-environment|com.apple.developer.icloud-container-environment)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

profile_supports_required_entitlements() {
  local plist_path="$1"
  local bundle_id="$2"
  local key
  local kind
  local index
  local expected
  local actual

  if [[ ! -f "$ENTITLEMENTS_PATH" ]]; then
    return 0
  fi

  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    if ! /usr/libexec/PlistBuddy -c "Print :Entitlements:$key" "$plist_path" >/dev/null 2>&1; then
      return 1
    fi

    kind="$(plist_value_kind "$ENTITLEMENTS_PATH" "$key")"
    case "$kind" in
      array)
        index=0
        while expected="$(/usr/libexec/PlistBuddy -c "Print :$key:$index" "$ENTITLEMENTS_PATH" 2>/dev/null)"; do
          expected="$(expand_entitlement_value "$expected" "$bundle_id")"
          if ! plist_array_contains "$plist_path" "Entitlements:$key" "$expected"; then
            return 1
          fi
          index=$((index + 1))
        done
        ;;
      dict)
        # Capability dictionaries vary by portal state; presence is the portable compatibility check.
        ;;
      scalar)
        if entitlement_value_is_distribution_managed "$key"; then
          continue
        fi
        expected="$(/usr/libexec/PlistBuddy -c "Print :$key" "$ENTITLEMENTS_PATH" 2>/dev/null || true)"
        actual="$(/usr/libexec/PlistBuddy -c "Print :Entitlements:$key" "$plist_path" 2>/dev/null || true)"
        expected="$(expand_entitlement_value "$expected" "$bundle_id")"
        if [[ "$actual" != "$expected" ]]; then
          return 1
        fi
        ;;
    esac
  done < <(required_entitlement_keys)

  return 0
}

profile_matches_export() {
  local profile_path="$1"
  local bundle_id="$2"
  local plist_path="$PRIVATE_TEMP_DIR/profile-check.plist"
  local expected_app_id="$TEAM_ID.$bundle_id"
  local app_id
  local profile_team
  local get_task_allow

  MATCHED_SIGNING_CERTIFICATE=""
  if ! openssl smime -inform DER -verify -noverify -in "$profile_path" -out "$plist_path" >/dev/null 2>&1; then
    rm -f "$plist_path"
    return 1
  fi

  app_id="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' "$plist_path" 2>/dev/null || true)"
  profile_team="$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' "$plist_path" 2>/dev/null || true)"
  get_task_allow="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:get-task-allow' "$plist_path" 2>/dev/null || true)"

  if [[ "$app_id" != "$expected_app_id" || "$profile_team" != "$TEAM_ID" || "$get_task_allow" != "false" ]]; then
    rm -f "$plist_path"
    return 1
  fi

  if /usr/libexec/PlistBuddy -c 'Print :ProvisionedDevices' "$plist_path" >/dev/null 2>&1; then
    rm -f "$plist_path"
    return 1
  fi

  if /usr/libexec/PlistBuddy -c 'Print :ProvisionsAllDevices' "$plist_path" >/dev/null 2>&1; then
    rm -f "$plist_path"
    return 1
  fi

  if ! profile_is_current "$plist_path"; then
    rm -f "$plist_path"
    return 1
  fi

  if ! profile_supports_required_entitlements "$plist_path" "$bundle_id"; then
    rm -f "$plist_path"
    return 1
  fi

  if ! profile_matching_signing_certificate "$plist_path"; then
    rm -f "$plist_path"
    return 1
  fi

  rm -f "$plist_path"
  return 0
}

profile_name() {
  local profile_path="$1"
  local plist_path="$PRIVATE_TEMP_DIR/profile-name.plist"
  local name

  if ! openssl smime -inform DER -verify -noverify -in "$profile_path" -out "$plist_path" >/dev/null 2>&1; then
    rm -f "$plist_path"
    return 1
  fi
  name="$(/usr/libexec/PlistBuddy -c 'Print :Name' "$plist_path" 2>/dev/null || true)"
  rm -f "$plist_path"

  if [[ -z "$name" ]]; then
    return 1
  fi
  printf '%s\n' "$name"
}

profile_uuid() {
  local profile_path="$1"
  local plist_path="$PRIVATE_TEMP_DIR/profile-uuid.plist"
  local uuid

  if ! openssl smime -inform DER -verify -noverify -in "$profile_path" -out "$plist_path" >/dev/null 2>&1; then
    rm -f "$plist_path"
    return 1
  fi
  uuid="$(/usr/libexec/PlistBuddy -c 'Print :UUID' "$plist_path" 2>/dev/null || true)"
  rm -f "$plist_path"

  if [[ -z "$uuid" ]]; then
    return 1
  fi
  printf '%s\n' "$uuid"
}

profile_specifier_matches() {
  local profile_path="$1"
  local requested="$2"
  local name
  local uuid

  name="$(profile_name "$profile_path" || true)"
  uuid="$(profile_uuid "$profile_path" || true)"
  [[ "$requested" == "$name" || "$requested" == "$uuid" ]]
}

resolve_installed_provisioning_profile() {
  local bundle_id="$1"
  local requested="${2:-}"
  local dir
  local name
  local profile_path
  local uuid

  for dir in \
    "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles" \
    "$HOME/Library/MobileDevice/Provisioning Profiles"; do
    [[ -d "$dir" ]] || continue
    for profile_path in "$dir"/*.mobileprovision "$dir"/*.provisionprofile; do
      [[ -f "$profile_path" ]] || continue
      if [[ -n "$requested" ]] && ! profile_specifier_matches "$profile_path" "$requested"; then
        continue
      fi
      if profile_matches_export "$profile_path" "$bundle_id"; then
        name="$(profile_name "$profile_path" || true)"
        uuid="$(profile_uuid "$profile_path" || true)"
        if [[ -z "$name" || -z "$uuid" || -z "$MATCHED_SIGNING_CERTIFICATE" ]]; then
          continue
        fi
        RESOLVED_PROVISIONING_PROFILE_NAME="$name"
        RESOLVED_PROVISIONING_PROFILE_SPECIFIER="$uuid"
        RESOLVED_SIGNING_CERTIFICATE="$MATCHED_SIGNING_CERTIFICATE"
        return 0
      fi
    done
  done

  return 1
}

project_release_bundle_id() {
  local build_settings
  local bundle_ids

  if ! build_settings="$(
    xcodebuild \
      -project "$PROJECT" \
      -scheme "$SCHEME" \
      -configuration "$CONFIGURATION" \
      -destination "generic/platform=iOS" \
      "${build_overrides[@]}" \
      -showBuildSettings 2>/dev/null
  )"; then
    return 1
  fi
  bundle_ids="$(
    printf '%s\n' "$build_settings" |
      sed -n 's/^[[:space:]]*PRODUCT_BUNDLE_IDENTIFIER = //p' |
      sort -u
  )"
  if [[ -z "$bundle_ids" || "$bundle_ids" == *$'\n'* ]]; then
    return 1
  fi
  printf '%s\n' "$bundle_ids"
}

resolve_release_bundle_id() {
  local actual_bundle_id

  if [[ "$DO_ARCHIVE" == 1 ]]; then
    actual_bundle_id="$(project_release_bundle_id || true)"
  else
    actual_bundle_id="$(archive_bundle_id || true)"
  fi
  if [[ -z "$actual_bundle_id" ]]; then
    echo "Unable to determine the iOS app bundle ID for signing." >&2
    exit 1
  fi
  if [[ -n "$EXPORT_BUNDLE_ID" && "$EXPORT_BUNDLE_ID" != "$actual_bundle_id" ]]; then
    echo "IOS_EXPORT_BUNDLE_ID does not match the app bundle ID." >&2
    exit 1
  fi
  RESOLVED_EXPORT_BUNDLE_ID="$actual_bundle_id"
}

resolve_release_signing() {
  RESOLVED_PROVISIONING_PROFILE_SPECIFIER=""
  RESOLVED_PROVISIONING_PROFILE_NAME=""
  RESOLVED_SIGNING_CERTIFICATE=""
  resolve_release_bundle_id

  if resolve_installed_provisioning_profile \
    "$RESOLVED_EXPORT_BUNDLE_ID" \
    "$PROVISIONING_PROFILE_SPECIFIER"; then
    return 0
  fi

  if [[ -n "$PROVISIONING_PROFILE_SPECIFIER" || "$SIGNING_CERTIFICATE_PROVIDED" == 1 ]]; then
    echo "The configured App Store signing profile/certificate is unavailable, incompatible, expired, or not installed with its private key." >&2
    exit 1
  fi
  if [[ "$ALLOW_PROVISIONING_UPDATES" == 1 ]]; then
    return 0
  fi

  echo "No compatible App Store provisioning profile and installed signing identity pair was found." >&2
  echo "Install a matching Apple Distribution certificate/profile pair, or explicitly enable IOS_ALLOW_PROVISIONING_UPDATES=true." >&2
  exit 1
}

write_export_options() {
  local path="$1"
  local destination="$2"
  local signing_options
  local icloud_options=""
  local bundle_id="$RESOLVED_EXPORT_BUNDLE_ID"

  if [[ -n "$RESOLVED_PROVISIONING_PROFILE_SPECIFIER" ]]; then
    # Pin both values so Xcode cannot independently select the newest certificate
    # behind a generic "Apple Distribution" selector.
    signing_options=$(cat <<EOF
	<key>provisioningProfiles</key>
	<dict>
		<key>$(xml_escape "$bundle_id")</key>
		<string>$(xml_escape "$RESOLVED_PROVISIONING_PROFILE_SPECIFIER")</string>
	</dict>
	<key>signingCertificate</key>
	<string>$(xml_escape "$RESOLVED_SIGNING_CERTIFICATE")</string>
	<key>signingStyle</key>
	<string>manual</string>
EOF
)
  else
    signing_options=$(cat <<'EOF'
	<key>signingStyle</key>
	<string>automatic</string>
EOF
)
  fi

  if entitlements_has_key "com.apple.developer.icloud-container-identifiers" ||
    entitlements_has_key "com.apple.developer.ubiquity-container-identifiers"; then
    icloud_options=$(cat <<'EOF'
	<key>iCloudContainerEnvironment</key>
	<string>Production</string>
EOF
)
  fi

  mkdir -p "$(dirname "$path")"
  cat > "$path" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>destination</key>
	<string>$(xml_escape "$destination")</string>
	<key>manageAppVersionAndBuildNumber</key>
	<false/>
	<key>method</key>
	<string>app-store-connect</string>
$icloud_options
$signing_options
	<key>stripSwiftSymbols</key>
	<true/>
	<key>teamID</key>
	<string>$(xml_escape "$TEAM_ID")</string>
	<key>uploadSymbols</key>
	<true/>
</dict>
</plist>
EOF
}

run_export() {
  local destination="$1"
  local export_path="$2"
  local export_options
  local ipa_path

  if [[ "$destination" == "upload" ]]; then
    require_upload_auth "$destination"
  fi

  prepare_output_path "$export_path"
  export_options="$PRIVATE_TEMP_DIR/ExportOptions-$destination.plist"
  write_export_options "$export_options" "$destination"

  if [[ "$destination" == "upload" ]]; then
    section "☁️" "Upload to TestFlight"
    detail "Archive" "$(display_path "$ARCHIVE_PATH")"
    if has_app_store_connect_auth; then
      detail "Auth" "App Store Connect API key configured"
    else
      detail "Auth" "Xcode Accounts"
    fi
  else
    section "📤" "Export IPA"
    detail "Output" "$(display_path "$export_path")"
  fi
  if [[ -n "$RESOLVED_PROVISIONING_PROFILE_SPECIFIER" ]]; then
    detail "Profile" "resolved"
  fi

  run_xcodebuild "${xcode_quiet_arg[@]}" \
    -exportArchive \
    -archivePath "$ARCHIVE_PATH" \
    -exportPath "$export_path" \
    -exportOptionsPlist "$export_options" \
    "${provisioning_args[@]}"

  if [[ "$destination" == "upload" ]]; then
    success "Upload submitted from the configured archive."
    print_testflight_link
  else
    ipa_path="$(find "$export_path" -maxdepth 1 -name '*.ipa' -print -quit)"
    if [[ -n "$ipa_path" ]]; then
      success "IPA written: $(display_path "$ipa_path")"
    else
      success "Export completed: $(display_path "$export_path")"
    fi
  fi
}

if [[ "$DO_EXPORT" == 1 && "$DESTINATION" == "upload" ]]; then
  require_upload_auth "$DESTINATION"
fi
if [[ "$DO_EXPORT" == 1 ]]; then
  resolve_release_signing
fi
print_release_summary

if [[ "$DO_ARCHIVE" == 1 ]]; then
  build_someday_ios_for_archive
  prepare_output_path "$ARCHIVE_PATH"
  section "📦" "Archive"
  detail "Output" "$(display_path "$ARCHIVE_PATH")"
  # Prefer an environment variable so the Xcode shell script phase can skip Gradle.
  SOMEDAY_SKIP_FRAMEWORK_BUILD=1 run_xcodebuild "${xcode_quiet_arg[@]}" \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination "generic/platform=iOS" \
    -archivePath "$ARCHIVE_PATH" \
    -derivedDataPath "$DERIVED_DATA" \
    "${build_overrides[@]}" \
    "${provisioning_args[@]}" \
    archive
  success "Archive written: $(display_path "$ARCHIVE_PATH")"
fi

if [[ "$DO_EXPORT" == 1 ]]; then
  run_export "$DESTINATION" "$EXPORT_PATH"
  if [[ "$DESTINATION" == "export" ]]; then
    print_upload_command
  fi
fi
