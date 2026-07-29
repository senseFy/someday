#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAB_PATH="${ANDROID_PLAY_AAB_PATH:-$ROOT_DIR/app/android/build/outputs/bundle/release/android-release.aab}"
PACKAGE_NAME="${ANDROID_PLAY_PACKAGE_NAME:-saien.someday}"
TRACK="${ANDROID_PLAY_TRACK:-internal}"
RELEASE_STATUS="${ANDROID_PLAY_RELEASE_STATUS:-completed}"
RELEASE_NAME="${ANDROID_PLAY_RELEASE_NAME:-}"
CREDENTIALS_PATH="${ANDROID_PLAY_SERVICE_ACCOUNT_JSON:-${GOOGLE_APPLICATION_CREDENTIALS:-}}"
ACCESS_TOKEN="${GOOGLE_PLAY_ACCESS_TOKEN:-}"
VERIFY_SCRIPT="$ROOT_DIR/scripts/verify-play-aab.sh"
CURL_BIN="${CURL_BIN:-curl}"

API_BASE="https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD_BASE="https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
TOKEN_URL="https://oauth2.googleapis.com/token"
ANDROID_PUBLISHER_SCOPE="https://www.googleapis.com/auth/androidpublisher"

DRY_RUN=0
CONFIRM_PRODUCTION=0
TMP_DIR=""
EDIT_ID=""
EDIT_COMMITTED=0

usage() {
  cat <<'EOF'
Upload a verified Someday Android App Bundle to a Google Play release track.

The script uses one Google Play Edit transaction:
  create edit -> upload bundle -> update track -> validate edit -> commit edit

Usage:
  ./scripts/upload-google-play.sh [options]

Options:
  --aab <path>                 AAB to upload. Also ANDROID_PLAY_AAB_PATH.
  --package-name <id>         Android application ID. Defaults to saien.someday.
                              Also ANDROID_PLAY_PACKAGE_NAME.
  --track <track>             Google Play track. Defaults to internal.
                              Also ANDROID_PLAY_TRACK.
  --status <status>           Release status: completed or draft.
                              Defaults to completed. Also ANDROID_PLAY_RELEASE_STATUS.
  --release-name <name>       Optional Google Play release name.
                              Also ANDROID_PLAY_RELEASE_NAME.
  --credentials <path>        Google service account JSON kept outside the repository.
                              Also ANDROID_PLAY_SERVICE_ACCOUNT_JSON or
                              GOOGLE_APPLICATION_CREDENTIALS.
  --confirm-production        Required for production or *:production tracks.
                              Also ANDROID_PLAY_CONFIRM_PRODUCTION=yes.
  --dry-run                   Verify the AAB and print the plan without API calls.
                              Also ANDROID_PLAY_DRY_RUN=yes.
  --help                      Show this help.

Authentication:
  Prefer --credentials with a dedicated Google Play publishing service account.
  GOOGLE_PLAY_ACCESS_TOKEN may instead contain a short-lived OAuth access token.
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

is_truthy() {
  case "${1:-}" in
    yes|YES|y|Y|true|TRUE|1) return 0 ;;
    *) return 1 ;;
  esac
}

is_production_track() {
  [[ "$TRACK" == "production" || "$TRACK" == *":production" ]]
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command is unavailable: $1"
}

base64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

print_error_response() {
  local response_file="$1"
  local message=""
  message="$(jq -r '.error.message // .error_description // empty' "$response_file" 2>/dev/null || true)"
  if [[ -n "$message" ]]; then
    printf '%s\n' "$message" >&2
  elif [[ -s "$response_file" ]]; then
    head -c 4000 "$response_file" >&2
    printf '\n' >&2
  fi
}

cleanup() {
  local exit_code=$?
  trap - EXIT
  if [[ -n "$EDIT_ID" && "$EDIT_COMMITTED" -eq 0 && -n "$ACCESS_TOKEN" ]]; then
    printf 'Discarding uncommitted Google Play edit: %s\n' "$EDIT_ID" >&2
    "$CURL_BIN" \
      --silent \
      --show-error \
      --request DELETE \
      --header "Authorization: Bearer $ACCESS_TOKEN" \
      "$API_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID" \
      >/dev/null 2>&1 || true
  fi
  if [[ -n "$TMP_DIR" ]]; then
    rm -rf "$TMP_DIR"
  fi
  exit "$exit_code"
}

mint_service_account_access_token() {
  local credentials_path="$1"
  local client_email=""
  local private_key_path="$TMP_DIR/service-account-private-key.pem"
  local issued_at=""
  local expires_at=""
  local header=""
  local claims=""
  local unsigned_token=""
  local signature=""
  local assertion=""
  local response_file="$TMP_DIR/oauth-token-response.json"
  local http_code=""

  client_email="$(
    jq -er 'select(.type == "service_account") | .client_email | select(length > 0)' \
      "$credentials_path"
  )" || die "Credentials must be a Google service account JSON file."
  jq -er '.private_key | select(length > 0)' "$credentials_path" > "$private_key_path" \
    || die "Service account JSON does not contain a private key."
  chmod 600 "$private_key_path"

  issued_at="$(date +%s)"
  expires_at="$((issued_at + 3600))"
  header="$(printf '%s' '{"alg":"RS256","typ":"JWT"}' | base64url)"
  claims="$(
    jq -cn \
      --arg iss "$client_email" \
      --arg scope "$ANDROID_PUBLISHER_SCOPE" \
      --arg aud "$TOKEN_URL" \
      --argjson iat "$issued_at" \
      --argjson exp "$expires_at" \
      '{iss: $iss, scope: $scope, aud: $aud, iat: $iat, exp: $exp}'
  )"
  unsigned_token="$header.$(printf '%s' "$claims" | base64url)"
  signature="$(
    printf '%s' "$unsigned_token" \
      | openssl dgst -sha256 -sign "$private_key_path" -binary \
      | base64url
  )" || die "Unable to sign the service account OAuth assertion."
  assertion="$unsigned_token.$signature"

  printf 'Authenticating Google Play publisher with configured service account.\n' >&2
  if ! http_code="$(
    "$CURL_BIN" \
      --silent \
      --show-error \
      --connect-timeout 20 \
      --max-time 60 \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --request POST \
      --header 'Content-Type: application/x-www-form-urlencoded' \
      --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
      --data-urlencode "assertion=$assertion" \
      "$TOKEN_URL"
  )"; then
    die "Unable to request a Google OAuth access token."
  fi
  if [[ "$http_code" != 2* ]]; then
    printf 'Google OAuth token request failed (HTTP %s): ' "$http_code" >&2
    print_error_response "$response_file"
    exit 1
  fi

  jq -er '.access_token | select(length > 0)' "$response_file" \
    || die "Google OAuth response did not contain an access token."
}

api_request() {
  local method="$1"
  local url="$2"
  local response_file="$3"
  local label="$4"
  local body_file="${5:-}"
  local http_code=""
  local args=(
    --silent
    --show-error
    --connect-timeout 20
    --max-time 180
    --output "$response_file"
    --write-out '%{http_code}'
    --request "$method"
    --header "Authorization: Bearer $ACCESS_TOKEN"
  )

  if [[ -n "$body_file" ]]; then
    args+=(
      --header 'Content-Type: application/json; charset=UTF-8'
      --data-binary "@$body_file"
    )
  elif [[ "$method" == "POST" || "$method" == "PUT" || "$method" == "PATCH" ]]; then
    args+=(
      --header 'Content-Type: application/json; charset=UTF-8'
      --data-binary ''
    )
  fi

  if ! http_code="$("$CURL_BIN" "${args[@]}" "$url")"; then
    die "$label request failed before receiving an HTTP response."
  fi
  if [[ "$http_code" != 2* ]]; then
    printf '%s failed (HTTP %s): ' "$label" "$http_code" >&2
    print_error_response "$response_file"
    exit 1
  fi
}

upload_bundle() {
  local response_file="$1"
  local http_code=""
  local upload_url="$UPLOAD_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID/bundles?uploadType=media"

  if ! http_code="$(
    "$CURL_BIN" \
      --silent \
      --show-error \
      --connect-timeout 20 \
      --max-time 600 \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --request POST \
      --header "Authorization: Bearer $ACCESS_TOKEN" \
      --header 'Content-Type: application/octet-stream' \
      --upload-file "$AAB_PATH" \
      "$upload_url"
  )"; then
    die "Android App Bundle upload failed before receiving an HTTP response."
  fi
  if [[ "$http_code" != 2* ]]; then
    printf 'Android App Bundle upload failed (HTTP %s): ' "$http_code" >&2
    print_error_response "$response_file"
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --aab)
      AAB_PATH="${2:-}"
      shift 2
      ;;
    --package-name)
      PACKAGE_NAME="${2:-}"
      shift 2
      ;;
    --track)
      TRACK="${2:-}"
      shift 2
      ;;
    --status)
      RELEASE_STATUS="${2:-}"
      shift 2
      ;;
    --release-name)
      RELEASE_NAME="${2:-}"
      shift 2
      ;;
    --credentials)
      CREDENTIALS_PATH="${2:-}"
      shift 2
      ;;
    --confirm-production)
      CONFIRM_PRODUCTION=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage
      exit 2
      ;;
  esac
done

if is_truthy "${ANDROID_PLAY_DRY_RUN:-}"; then
  DRY_RUN=1
fi
if is_truthy "${ANDROID_PLAY_CONFIRM_PRODUCTION:-}"; then
  CONFIRM_PRODUCTION=1
fi

[[ -n "$AAB_PATH" && -f "$AAB_PATH" ]] || die "Google Play AAB not found: $AAB_PATH"
[[ "$PACKAGE_NAME" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] \
  || die "Invalid Android package name: $PACKAGE_NAME"
[[ "$TRACK" =~ ^[A-Za-z0-9._:-]+$ ]] || die "Invalid Google Play track: $TRACK"
case "$RELEASE_STATUS" in
  completed|draft) ;;
  *) die "Release status must be completed or draft." ;;
esac

if is_production_track && [[ "$DRY_RUN" -eq 0 && "$CONFIRM_PRODUCTION" -ne 1 ]]; then
  die "Production upload requires --confirm-production or ANDROID_PLAY_CONFIRM_PRODUCTION=yes."
fi

[[ -x "$VERIFY_SCRIPT" ]] || die "AAB verifier is unavailable: $VERIFY_SCRIPT"
printf 'Verifying Google Play App Bundle before upload...\n'
ANDROID_PLAY_PACKAGE_NAME="$PACKAGE_NAME" "$VERIFY_SCRIPT" "$AAB_PATH"

printf 'Google Play release plan:\n'
printf '  AAB: %s\n' "$AAB_PATH"
printf '  Package: %s\n' "$PACKAGE_NAME"
printf '  Track: %s\n' "$TRACK"
printf '  Status: %s\n' "$RELEASE_STATUS"
if [[ -n "$RELEASE_NAME" ]]; then
  printf '  Release name: %s\n' "$RELEASE_NAME"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  printf 'Dry run completed; no Google Play API calls were made.\n'
  exit 0
fi

require_command "$CURL_BIN"
require_command jq
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/someday-google-play-upload.XXXXXX")"
trap cleanup EXIT

if [[ -z "$ACCESS_TOKEN" ]]; then
  [[ -n "$CREDENTIALS_PATH" ]] \
    || die "Pass --credentials, set ANDROID_PLAY_SERVICE_ACCOUNT_JSON or GOOGLE_APPLICATION_CREDENTIALS, or export GOOGLE_PLAY_ACCESS_TOKEN."
  [[ -f "$CREDENTIALS_PATH" ]] || die "Configured service account JSON path does not point to a file."
  require_command openssl
  ACCESS_TOKEN="$(mint_service_account_access_token "$CREDENTIALS_PATH")"
fi

CREATE_BODY="$TMP_DIR/create-edit.json"
CREATE_RESPONSE="$TMP_DIR/create-edit-response.json"
printf '{}\n' > "$CREATE_BODY"
printf 'Creating Google Play edit...\n'
api_request \
  POST \
  "$API_BASE/applications/$PACKAGE_NAME/edits" \
  "$CREATE_RESPONSE" \
  "Create edit" \
  "$CREATE_BODY"
EDIT_ID="$(jq -er '.id | select(length > 0)' "$CREATE_RESPONSE")" \
  || die "Google Play did not return an edit ID."

UPLOAD_RESPONSE="$TMP_DIR/upload-bundle-response.json"
printf 'Uploading Android App Bundle to edit %s...\n' "$EDIT_ID"
upload_bundle "$UPLOAD_RESPONSE"
VERSION_CODE="$(jq -er '.versionCode | tostring | select(length > 0)' "$UPLOAD_RESPONSE")" \
  || die "Google Play did not return the uploaded versionCode."

TRACK_BODY="$TMP_DIR/update-track.json"
TRACK_RESPONSE="$TMP_DIR/update-track-response.json"
jq -n \
  --arg track "$TRACK" \
  --arg versionCode "$VERSION_CODE" \
  --arg status "$RELEASE_STATUS" \
  --arg name "$RELEASE_NAME" \
  '{
    track: $track,
    releases: [
      ({versionCodes: [$versionCode], status: $status}
        + (if $name == "" then {} else {name: $name} end))
    ]
  }' > "$TRACK_BODY"
printf 'Updating Google Play track %s with versionCode %s...\n' "$TRACK" "$VERSION_CODE"
api_request \
  PUT \
  "$API_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID/tracks/$TRACK" \
  "$TRACK_RESPONSE" \
  "Update track" \
  "$TRACK_BODY"

VALIDATE_RESPONSE="$TMP_DIR/validate-edit-response.json"
printf 'Validating Google Play edit...\n'
api_request \
  POST \
  "$API_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID:validate" \
  "$VALIDATE_RESPONSE" \
  "Validate edit"

COMMIT_RESPONSE="$TMP_DIR/commit-edit-response.json"
printf 'Committing Google Play edit...\n'
api_request \
  POST \
  "$API_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW" \
  "$COMMIT_RESPONSE" \
  "Commit edit"
EDIT_COMMITTED=1

printf 'Google Play upload committed successfully.\n'
printf '  Package: %s\n' "$PACKAGE_NAME"
printf '  Track: %s\n' "$TRACK"
printf '  Status: %s\n' "$RELEASE_STATUS"
printf '  Version code: %s\n' "$VERSION_CODE"
if [[ "$RELEASE_STATUS" == "draft" ]]; then
  printf '  The release is a draft and is not being served to testers.\n'
else
  printf '  Google Play may still need time to process the release.\n'
fi
