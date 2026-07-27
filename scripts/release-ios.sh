#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT_DIR/iosApp/Someday.xcodeproj"
SCHEME="Someday"
CONFIGURATION="Release"
DERIVED_DATA="$ROOT_DIR/iosApp/build/XcodeDerivedData"
ENTITLEMENTS_PATH="$ROOT_DIR/iosApp/Someday/Someday.entitlements"
GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
FRAMEWORK_PATH="$ROOT_DIR/app/ios/build/bin/iosArm64/releaseFramework/SomedayIos.framework"
IOS_GRADLE_MAX_WORKERS="${IOS_GRADLE_MAX_WORKERS:-2}"

TEAM_ID="${IOS_TEAM_ID:-${IOS_DEVELOPMENT_TEAM:-}}"
ARCHIVE_PATH="${IOS_ARCHIVE_PATH:-$ROOT_DIR/iosApp/build/Someday-Release.xcarchive}"
EXPORT_PATH="${IOS_EXPORT_PATH:-$ROOT_DIR/iosApp/build/AppStoreExport}"
EXPORT_PATH_PROVIDED=0
if [[ -n "${IOS_EXPORT_PATH:-}" ]]; then
  EXPORT_PATH_PROVIDED=1
fi
UPLOAD_EXPORT_PATH="${IOS_UPLOAD_EXPORT_PATH:-$ROOT_DIR/iosApp/build/AppStoreUpload}"
BUILD_NUMBER="${IOS_BUILD_NUMBER:-}"
MARKETING_VERSION="${IOS_MARKETING_VERSION:-}"
BUMP_IOS_VERSION="${IOS_BUMP_VERSION:-auto}"
APP_STORE_CONNECT_APP_ID="${APP_STORE_CONNECT_APP_ID:-${ASC_APP_ID:-}}"
PROVISIONING_PROFILE_SPECIFIER="${IOS_PROVISIONING_PROFILE_SPECIFIER:-${IOS_EXPORT_PROVISIONING_PROFILE:-}}"
EXPORT_BUNDLE_ID="${IOS_EXPORT_BUNDLE_ID:-}"
SIGNING_CERTIFICATE="${IOS_SIGNING_CERTIFICATE:-Apple Distribution}"
RESOLVED_PROVISIONING_PROFILE_SPECIFIER=""
UPLOAD_AUTH_MODE="${IOS_UPLOAD_AUTH_MODE:-api-key}"

AUTH_KEY_PATH="${APP_STORE_CONNECT_API_KEY_PATH:-${ASC_KEY_PATH:-${EXPO_ASC_API_KEY_PATH:-}}}"
AUTH_KEY_ID="${APP_STORE_CONNECT_API_KEY_ID:-${ASC_KEY_ID:-${EXPO_ASC_KEY_ID:-}}}"
AUTH_KEY_ISSUER_ID="${APP_STORE_CONNECT_API_ISSUER_ID:-${ASC_ISSUER_ID:-${EXPO_ASC_ISSUER_ID:-}}}"

DO_ARCHIVE=1
DO_EXPORT=1
DESTINATION="export"
ALLOW_PROVISIONING_UPDATES=1
CLEAN_CUSTOM_PATHS=0
VERBOSE=0

usage() {
  cat <<'EOF'
Archive and export the Someday iOS app for App Store Connect / TestFlight.

Default behavior:
  - Prebuild the Release SomedayIos.framework with developer options disabled.
  - Build a signed Release archive.
  - Export a local App Store Connect IPA.
  - Print a reusable upload command after local export.
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
  --bump-version                  Bump iOS build number and commit before archiving.
  --no-bump-version               Do not prompt for or run an iOS version bump.
  --app-store-connect-app-id <id> App Store Connect app ID for direct TestFlight links.
                                  Also APP_STORE_CONNECT_APP_ID or ASC_APP_ID.
  --provisioning-profile <name>   Export with a specific App Store provisioning profile.
                                  Also IOS_PROVISIONING_PROFILE_SPECIFIER.
  --bundle-id <id>                Bundle ID for manual export profile mapping.
                                  Defaults to the archive bundle ID. Also IOS_EXPORT_BUNDLE_ID.
  --signing-certificate <name>    Signing certificate selector for manual export.
                                  Defaults to "Apple Distribution". Also IOS_SIGNING_CERTIFICATE.
                                  If omitted, the script tries to find a matching installed
                                  App Store profile before falling back to automatic signing.
  --archive-only                  Stop after creating the .xcarchive.
  --export-only                   Export an existing archive without archiving.
  --upload                        Upload to App Store Connect instead of exporting a local IPA.
  --upload-auth-mode <mode>       Upload auth mode: api-key or accounts.
                                  Defaults to api-key. Also IOS_UPLOAD_AUTH_MODE.
  --no-allow-provisioning-updates  Do not let Xcode update certificates/profiles.
  --clean                         Allow deleting custom archive/export paths before writing.
  --verbose                       Print full xcodebuild output.

App Store Connect API key options:
  --auth-key-path <path>          Also APP_STORE_CONNECT_API_KEY_PATH, ASC_KEY_PATH,
                                  or EXPO_ASC_API_KEY_PATH.
  --auth-key-id <id>              Also APP_STORE_CONNECT_API_KEY_ID, ASC_KEY_ID,
                                  or EXPO_ASC_KEY_ID.
  --auth-key-issuer-id <id>       Also APP_STORE_CONNECT_API_ISSUER_ID, ASC_ISSUER_ID,
                                  or EXPO_ASC_ISSUER_ID.

Environment:
  IOS_GRADLE_MAX_WORKERS=<count>  Gradle workers used by the iOS prebuild. Defaults to 2.

Examples:
  IOS_TEAM_ID=YOUR_TEAM_ID ./scripts/release-ios.sh
  IOS_TEAM_ID=YOUR_TEAM_ID ./scripts/release-ios.sh --bump-version
  IOS_TEAM_ID=YOUR_TEAM_ID ./scripts/release-ios.sh --upload
  make ios-release IOS_TEAM_ID=YOUR_TEAM_ID
  make ios-upload-testflight IOS_TEAM_ID=YOUR_TEAM_ID
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
    --bump-version)
      BUMP_IOS_VERSION="yes"
      shift 1
      ;;
    --no-bump-version)
      BUMP_IOS_VERSION="no"
      shift 1
      ;;
    --app-store-connect-app-id|--asc-app-id)
      APP_STORE_CONNECT_APP_ID="${2:-}"
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
      ALLOW_PROVISIONING_UPDATES=1
      shift 1
      ;;
    --no-allow-provisioning-updates)
      ALLOW_PROVISIONING_UPDATES=0
      shift 1
      ;;
    --auth-key-path)
      AUTH_KEY_PATH="${2:-}"
      shift 2
      ;;
    --auth-key-id)
      AUTH_KEY_ID="${2:-}"
      shift 2
      ;;
    --auth-key-issuer-id)
      AUTH_KEY_ISSUER_ID="${2:-}"
      shift 2
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

if [[ -z "$TEAM_ID" ]]; then
  echo "Missing Apple Developer Team ID. Pass --team or IOS_TEAM_ID." >&2
  exit 1
fi

if [[ ! "$IOS_GRADLE_MAX_WORKERS" =~ ^[1-9][0-9]*$ ]]; then
  echo "IOS_GRADLE_MAX_WORKERS must be a positive integer: $IOS_GRADLE_MAX_WORKERS" >&2
  exit 1
fi

if [[ -n "$AUTH_KEY_PATH$AUTH_KEY_ID$AUTH_KEY_ISSUER_ID" ]]; then
  if [[ -z "$AUTH_KEY_PATH" || -z "$AUTH_KEY_ID" || -z "$AUTH_KEY_ISSUER_ID" ]]; then
    echo "App Store Connect API auth requires key path, key ID, and issuer ID." >&2
    exit 1
  fi
fi

case "$UPLOAD_AUTH_MODE" in
  api-key|accounts) ;;
  *)
    echo "Unsupported IOS_UPLOAD_AUTH_MODE value: $UPLOAD_AUTH_MODE. Use api-key or accounts." >&2
    exit 1
    ;;
esac

if [[ "$DO_ARCHIVE" == 0 && ! -d "$ARCHIVE_PATH" ]]; then
  echo "Archive not found: $ARCHIVE_PATH" >&2
  exit 1
fi

if [[ "$DESTINATION" == "upload" && "$EXPORT_PATH_PROVIDED" == 0 ]]; then
  EXPORT_PATH="$UPLOAD_EXPORT_PATH"
fi

absolute_path() {
  local path="$1"
  case "$path" in
    /*) printf '%s\n' "$path" ;;
    *) printf '%s\n' "$(pwd)/$path" ;;
  esac
}

ARCHIVE_PATH="$(absolute_path "$ARCHIVE_PATH")"
EXPORT_PATH="$(absolute_path "$EXPORT_PATH")"
UPLOAD_EXPORT_PATH="$(absolute_path "$UPLOAD_EXPORT_PATH")"
if [[ -n "$AUTH_KEY_PATH" ]]; then
  AUTH_KEY_PATH="$(absolute_path "$AUTH_KEY_PATH")"
  if [[ ! -f "$AUTH_KEY_PATH" ]]; then
    echo "App Store Connect API key file not found: $AUTH_KEY_PATH" >&2
    exit 1
  fi
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
  echo "Gradle wrapper is not executable: $GRADLEW" >&2
  exit 1
}
[[ -d "$PROJECT" ]] || {
  echo "Xcode project not found: $PROJECT" >&2
  exit 1
}

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

build_overrides=(
  DEVELOPMENT_TEAM="$TEAM_ID"
  CODE_SIGN_STYLE=Automatic
)
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
  detail "Team" "$TEAM_ID"
  if [[ -n "$PROVISIONING_PROFILE_SPECIFIER" ]]; then
    detail "Profile" "$PROVISIONING_PROFILE_SPECIFIER"
  fi
  detail "Archive" "$ARCHIVE_PATH"
}

run_xcodebuild() {
  if [[ "$VERBOSE" == 1 ]]; then
    xcodebuild "$@"
    return
  fi

  set +e
  xcodebuild "$@" 2>&1 | awk '
    /IDERunDestination: Supported platforms for the buildables in the current scheme is empty\./ { next }
    /IDEDistribution: -\[IDEDistributionLogging _createLoggingBundleAtPath:\]/ { next }
    {
      if ($0 == previous && $0 ~ /Progress [0-9]+%:/) {
        next
      }
      previous = $0
      print
    }
  '
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
  SDK_NAME=iphoneos \
  CONFIGURATION=Release \
  GRADLEW="$GRADLEW" \
  SOMEDAY_IOS_GRADLE_MAX_WORKERS="$IOS_GRADLE_MAX_WORKERS" \
    "$ROOT_DIR/scripts/build-ios-framework-for-xcode.sh"

  if [[ ! -d "$FRAMEWORK_PATH" || ! -f "$FRAMEWORK_PATH/SomedayIos" ]]; then
    echo "SomedayIos framework not found: $FRAMEWORK_PATH" >&2
    exit 1
  fi
  success "SomedayIos framework ready: $FRAMEWORK_PATH"
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

  echo "Refusing to delete custom output path without --clean: $path" >&2
  exit 1
}

current_ios_build_number() {
  sed -n 's/.*CURRENT_PROJECT_VERSION = \([^;]*\);.*/\1/p' "$PROJECT/project.pbxproj" | head -1
}

current_ios_marketing_version() {
  sed -n 's/.*MARKETING_VERSION = \([^;]*\);.*/\1/p' "$PROJECT/project.pbxproj" | head -1
}

run_ios_version_bump_if_needed() {
  if [[ "$DO_ARCHIVE" != 1 ]]; then
    return 0
  fi
  if [[ -n "$BUILD_NUMBER" || -n "$MARKETING_VERSION" ]]; then
    return 0
  fi

  case "$BUMP_IOS_VERSION" in
    yes|true|1) ;;
    no|false|0) return 0 ;;
    auto)
      if [[ ! -t 0 ]]; then
        return 0
      fi
      local current_version
      local current_build
      current_version="$(current_ios_marketing_version)"
      current_build="$(current_ios_build_number)"
      local answer
      printf '\n'
      read -r -p "🔖 Bump iOS build number? Current: ${current_version:-unknown} (${current_build:-unknown}) [y/N] " answer
      case "$answer" in
        y|Y|yes|YES) ;;
        *) return 0 ;;
      esac
      ;;
    *)
      echo "Unsupported IOS_BUMP_VERSION value: $BUMP_IOS_VERSION" >&2
      exit 1
      ;;
  esac

  "$ROOT_DIR/scripts/bump-mobile-version.sh" --platform ios
}

shell_quote() {
  printf '%q' "$1"
}

has_app_store_connect_auth() {
  [[ -n "$AUTH_KEY_PATH" && -n "$AUTH_KEY_ID" && -n "$AUTH_KEY_ISSUER_ID" ]]
}

print_upload_command() {
  section "⬆️" "Upload later"
  if has_app_store_connect_auth; then
    printf '   cd %s && \\\n' "$(shell_quote "$ROOT_DIR")"
    printf '     APP_STORE_CONNECT_API_KEY_PATH=%s \\\n' "$(shell_quote "$AUTH_KEY_PATH")"
    printf '     APP_STORE_CONNECT_API_KEY_ID=%s \\\n' "$(shell_quote "$AUTH_KEY_ID")"
    printf '     APP_STORE_CONNECT_API_ISSUER_ID=%s \\\n' "$(shell_quote "$AUTH_KEY_ISSUER_ID")"
    printf '     make ios-upload-archive \\\n'
    printf '       IOS_TEAM_ID=%s \\\n' "$(shell_quote "$TEAM_ID")"
    printf '       IOS_ARCHIVE_PATH=%s \\\n' "$(shell_quote "$ARCHIVE_PATH")"
    printf '       IOS_BUMP_VERSION=no\n'
  else
    detail "Auth" "set App Store Connect API key variables before upload"
    printf '   cd %s && \\\n' "$(shell_quote "$ROOT_DIR")"
    printf '     APP_STORE_CONNECT_API_KEY_PATH=/path/AuthKey_XXXXXX.p8 \\\n'
    printf '     APP_STORE_CONNECT_API_KEY_ID=XXXXXXXXXX \\\n'
    printf '     APP_STORE_CONNECT_API_ISSUER_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx \\\n'
    printf '     make ios-upload-archive \\\n'
    printf '       IOS_TEAM_ID=%s \\\n' "$(shell_quote "$TEAM_ID")"
    printf '       IOS_ARCHIVE_PATH=%s \\\n' "$(shell_quote "$ARCHIVE_PATH")"
    printf '       IOS_BUMP_VERSION=no\n'
  fi
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
  detail "Retry" "set those variables, or pass IOS_UPLOAD_AUTH_MODE=accounts to force Xcode Accounts"
  local exported_ipa="$ROOT_DIR/iosApp/build/AppStoreExport/Someday.ipa"
  if [[ -f "$exported_ipa" ]]; then
    detail "IPA" "$exported_ipa"
  fi
  print_upload_command
  exit 1
}

testflight_url() {
  if [[ -n "$APP_STORE_CONNECT_APP_ID" ]]; then
    printf 'https://appstoreconnect.apple.com/apps/%s/testflight/ios\n' "$APP_STORE_CONNECT_APP_ID"
  else
    printf 'https://appstoreconnect.apple.com/apps\n'
  fi
}

print_testflight_link() {
  section "🔗" "TestFlight"
  detail "Open" "$(testflight_url)"
  if [[ -z "$APP_STORE_CONNECT_APP_ID" ]]; then
    detail "Tip" "set APP_STORE_CONNECT_APP_ID=<app-id> for a direct TestFlight app link"
  fi
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

profile_certificate_matches() {
  local plist_path="$1"
  local index=0
  local cert_path
  local subject

  while true; do
    cert_path="/tmp/someday-profile-cert-${BASHPID:-$$}-$index.cer"
    if ! /usr/libexec/PlistBuddy -c "Print :DeveloperCertificates:$index" "$plist_path" > "$cert_path" 2>/dev/null; then
      rm -f "$cert_path"
      break
    fi

    subject="$(openssl x509 -inform DER -in "$cert_path" -noout -subject 2>/dev/null || true)"
    rm -f "$cert_path"
    if [[ "$subject" == *"$SIGNING_CERTIFICATE"* ]]; then
      return 0
    fi

    index=$((index + 1))
  done

  return 1
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
  local plist_path="/tmp/someday-profile-${BASHPID:-$$}.plist"
  local expected_app_id="$TEAM_ID.$bundle_id"
  local app_id
  local profile_team
  local get_task_allow

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

  if ! profile_certificate_matches "$plist_path"; then
    rm -f "$plist_path"
    return 1
  fi

  if ! profile_supports_required_entitlements "$plist_path" "$bundle_id"; then
    rm -f "$plist_path"
    return 1
  fi

  rm -f "$plist_path"
  return 0
}

profile_name() {
  local profile_path="$1"
  local plist_path="/tmp/someday-profile-name-${BASHPID:-$$}.plist"
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

find_installed_provisioning_profile() {
  local bundle_id="$1"
  local dir
  local profile_path

  for dir in \
    "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles" \
    "$HOME/Library/MobileDevice/Provisioning Profiles"; do
    [[ -d "$dir" ]] || continue
    for profile_path in "$dir"/*.mobileprovision "$dir"/*.provisionprofile; do
      [[ -f "$profile_path" ]] || continue
      if profile_matches_export "$profile_path" "$bundle_id"; then
        profile_name "$profile_path"
        return 0
      fi
    done
  done

  return 1
}

write_export_options() {
  local path="$1"
  local destination="$2"
  local signing_options
  local icloud_options=""
  local bundle_id="$EXPORT_BUNDLE_ID"
  local profile_specifier="$PROVISIONING_PROFILE_SPECIFIER"

  if [[ -z "$bundle_id" ]]; then
    bundle_id="$(archive_bundle_id || true)"
  fi

  if [[ -z "$profile_specifier" && -n "$bundle_id" ]]; then
    profile_specifier="$(find_installed_provisioning_profile "$bundle_id" || true)"
  fi

  RESOLVED_PROVISIONING_PROFILE_SPECIFIER="$profile_specifier"

  if [[ -n "$profile_specifier" ]]; then
    if [[ -z "$bundle_id" ]]; then
      echo "Unable to determine bundle ID for manual export. Pass --bundle-id or IOS_EXPORT_BUNDLE_ID." >&2
      exit 1
    fi
    signing_options=$(cat <<EOF
	<key>provisioningProfiles</key>
	<dict>
		<key>$(xml_escape "$bundle_id")</key>
		<string>$(xml_escape "$profile_specifier")</string>
	</dict>
	<key>signingCertificate</key>
	<string>$(xml_escape "$SIGNING_CERTIFICATE")</string>
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
  export_options="$ROOT_DIR/iosApp/build/ExportOptions-$destination.plist"
  write_export_options "$export_options" "$destination"

  if [[ "$destination" == "upload" ]]; then
    section "☁️" "Upload to TestFlight"
    detail "Archive" "$ARCHIVE_PATH"
    if has_app_store_connect_auth; then
      detail "Auth" "App Store Connect API key $AUTH_KEY_ID"
      detail "Key" "$AUTH_KEY_PATH"
    else
      detail "Auth" "Xcode Accounts"
    fi
  else
    section "📤" "Export IPA"
    detail "Output" "$export_path"
  fi
  if [[ -n "$RESOLVED_PROVISIONING_PROFILE_SPECIFIER" ]]; then
    detail "Profile" "$RESOLVED_PROVISIONING_PROFILE_SPECIFIER"
  fi

  run_xcodebuild "${xcode_quiet_arg[@]}" \
    -exportArchive \
    -archivePath "$ARCHIVE_PATH" \
    -exportPath "$export_path" \
    -exportOptionsPlist "$export_options" \
    "${provisioning_args[@]}"

  if [[ "$destination" == "upload" ]]; then
    success "Upload submitted from archive: $ARCHIVE_PATH"
    print_testflight_link
  else
    ipa_path="$(find "$export_path" -maxdepth 1 -name '*.ipa' -print -quit)"
    if [[ -n "$ipa_path" ]]; then
      success "IPA written: $ipa_path"
    else
      success "Export completed: $export_path"
    fi
  fi
}

choose_export_destination() {
  if [[ "$DESTINATION" != "export" || "$DO_ARCHIVE" != 1 ]]; then
    return 0
  fi

  if [[ ! -t 0 ]]; then
    return 0
  fi

  local answer
  printf '\n'
  read -r -p "☁️  Upload the archive directly instead of exporting a local IPA? [y/N] " answer
  case "$answer" in
    y|Y|yes|YES)
      DESTINATION="upload"
      if [[ "$EXPORT_PATH_PROVIDED" == 0 ]]; then
        EXPORT_PATH="$UPLOAD_EXPORT_PATH"
      fi
      ;;
  esac
}

choose_export_destination
if [[ "$DO_EXPORT" == 1 && "$DESTINATION" == "upload" ]]; then
  require_upload_auth "$DESTINATION"
fi
print_release_summary
run_ios_version_bump_if_needed

if [[ "$DO_ARCHIVE" == 1 ]]; then
  build_someday_ios_for_archive
  prepare_output_path "$ARCHIVE_PATH"
  section "📦" "Archive"
  detail "Output" "$ARCHIVE_PATH"
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
  success "Archive written: $ARCHIVE_PATH"
fi

if [[ "$DO_EXPORT" == 1 ]]; then
  run_export "$DESTINATION" "$EXPORT_PATH"
  if [[ "$DESTINATION" == "export" ]]; then
    print_upload_command
  fi
fi
