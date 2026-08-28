#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
VERIFY_SCRIPT="$ROOT_DIR/scripts/verify-public-history"
FIXTURES="$(mktemp -d "${TMPDIR:-/tmp}/someday-public-history-test.XXXXXX")"
trap 'rm -rf "$FIXTURES"' EXIT HUP INT TERM
EMPTY_HOOKS="$FIXTURES/empty-hooks"
mkdir -p "$EMPTY_HOOKS"
export SOMEDAY_PUBLIC_HISTORY_MAX_BLOB_BYTES=10485760
export GIT_CONFIG_COUNT=3
export GIT_CONFIG_KEY_0=commit.gpgSign
export GIT_CONFIG_VALUE_0=false
export GIT_CONFIG_KEY_1=tag.gpgSign
export GIT_CONFIG_VALUE_1=false
export GIT_CONFIG_KEY_2=core.hooksPath
export GIT_CONFIG_VALUE_2="$EMPTY_HOOKS"
export GIT_AUTHOR_NAME='Public History Test'
export GIT_AUTHOR_EMAIL=developer@example.test
export GIT_COMMITTER_NAME='Public History Test'
export GIT_COMMITTER_EMAIL=developer@example.test

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

[[ -x "$VERIFY_SCRIPT" ]] ||
  fail "public-history verifier is missing or is not executable: $VERIFY_SCRIPT"

new_repo() {
  local repo="$1"

  git init -q "$repo"
  git -C "$repo" config user.name "Public History Test"
  git -C "$repo" config user.email "developer@example.test"
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config tag.gpgSign false
  git -C "$repo" config core.hooksPath "$EMPTY_HOOKS"
}

commit_all() {
  local repo="$1"
  local message="$2"

  git -C "$repo" add -f --all
  git -C "$repo" commit -q -m "$message"
}

expect_failure() {
  local repo="$1"
  local output="$2"

  if (cd "$repo" && "$VERIFY_SCRIPT" --quiet) > "$output" 2>&1; then
    fail "public-history verifier unexpectedly passed: $repo"
  fi
}

clean_repo="$FIXTURES/clean"
new_repo "$clean_repo"
mkdir -p "$clean_repo/scripts/tests"
cp "$VERIFY_SCRIPT" "$clean_repo/scripts/verify-public-history"
cp "$ROOT_DIR/scripts/tests/verify-public-history-test.sh" \
  "$clean_repo/scripts/tests/verify-public-history-test.sh"
printf 'SOMEDAY_TOKEN=\n' > "$clean_repo/.env.example"
printf '%s\n' '-----BEGIN CERTIFICATE-----' > "$clean_repo/public-cert.pem"
printf 'public certificate fixture\n' > "$clean_repo/public-cert.cer"
printf 'clean history\n' > "$clean_repo/README.md"
GIT_COMMITTER_EMAIL=committer@example.test \
  commit_all "$clean_repo" "Create clean fixture"

clean_output="$FIXTURES/clean-output"
(cd "$clean_repo" && "$VERIFY_SCRIPT") > "$clean_output"
grep -F 'Non-noreply history email count: 2' "$clean_output" >/dev/null ||
  fail "clean result did not count distinct author and committer emails"
if grep -Eq 'developer@example\.test|committer@example\.test' "$clean_output"; then
  fail "clean result disclosed a history email address"
fi

quiet_output="$FIXTURES/quiet-output"
(cd "$clean_repo" && "$VERIFY_SCRIPT" --quiet) > "$quiet_output" 2>&1
[[ ! -s "$quiet_output" ]] || fail "--quiet produced output for a clean history"

sensitive_path_repo="$FIXTURES/sensitive-path"
new_repo "$sensitive_path_repo"
printf 'clean\n' > "$sensitive_path_repo/README.md"
commit_all "$sensitive_path_repo" "Create path fixture"
mkdir -p "$sensitive_path_repo/.factory"
path_token_prefix='ghp_'
path_token="${path_token_prefix}000000000000000000000000000000000000"
printf 'fixture\n' > "$sensitive_path_repo/.env"
printf 'fixture\n' > "$sensitive_path_repo/.factory/operator.json"
printf 'fixture\n' > "$sensitive_path_repo/.factory/$path_token.txt"
printf 'fixture\n' > "$sensitive_path_repo/signing.p8"
commit_all "$sensitive_path_repo" "Add sensitive paths"
git -C "$sensitive_path_repo" rm -q -r -- .env .factory signing.p8
git -C "$sensitive_path_repo" commit -q -m "Remove sensitive paths"

path_output="$FIXTURES/path-output"
expect_failure "$sensitive_path_repo" "$path_output"
grep -F 'sensitive paths: 4 (names omitted)' "$path_output" >/dev/null ||
  fail "historical sensitive path count was not reported"
if grep -F "$path_token" "$path_output" >/dev/null; then
  fail "sensitive-path failure disclosed matching path content"
fi

shallow_repo="$FIXTURES/shallow"
git clone -q --depth 1 "file://$sensitive_path_repo" "$shallow_repo"
shallow_output="$FIXTURES/shallow-output"
expect_failure "$shallow_repo" "$shallow_output"
grep -F 'a complete clone is required' "$shallow_output" >/dev/null ||
  fail "shallow clone failed for the wrong reason"

token_repo="$FIXTURES/token"
new_repo "$token_repo"
printf 'clean\n' > "$token_repo/README.md"
commit_all "$token_repo" "Create token fixture"
token_prefix='AK'
token_prefix+='IA'
fake_token="${token_prefix}0000000000000000"
printf 'token=%s\n' "$fake_token" > "$token_repo/config.txt"
commit_all "$token_repo" "Add token fixture"
git -C "$token_repo" rm -q -- config.txt
git -C "$token_repo" commit -q -m "Remove token fixture"

token_output="$FIXTURES/token-output"
expect_failure "$token_repo" "$token_output"
grep -F 'sensitive content: reachable blob object(s)' "$token_output" >/dev/null ||
  fail "historical token content was not reported"
if grep -F "$fake_token" "$token_output" >/dev/null; then
  fail "token failure disclosed the matching content"
fi

private_key_repo="$FIXTURES/private-key"
new_repo "$private_key_repo"
key_begin='-----BE'
key_begin+='GIN'
key_marker="${key_begin} ENCRYPTED PRIVATE KEY-----"
printf '%s\nfixture\n' "$key_marker" > "$private_key_repo/document.txt"
commit_all "$private_key_repo" "Add private-key fixture"
git -C "$private_key_repo" rm -q -- document.txt
git -C "$private_key_repo" commit -q -m "Remove private-key fixture"

private_key_output="$FIXTURES/private-key-output"
expect_failure "$private_key_repo" "$private_key_output"
grep -F 'sensitive content: reachable blob object(s)' "$private_key_output" >/dev/null ||
  fail "historical private-key content was not reported"
if grep -F -- "$key_marker" "$private_key_output" >/dev/null; then
  fail "private-key failure disclosed the matching content"
fi

message_repo="$FIXTURES/messages"
new_repo "$message_repo"
printf 'clean\n' > "$message_repo/README.md"
commit_all "$message_repo" "Create message fixture"
message_prefix='glpat-'
commit_token="${message_prefix}00000000000000000000"
tag_prefix='sk_live_'
tag_token="${tag_prefix}00000000000000000000"
printf 'second\n' >> "$message_repo/README.md"
commit_all "$message_repo" "$commit_token"
git -C "$message_repo" tag -a message-fixture -m "$tag_token"

message_output="$FIXTURES/message-output"
expect_failure "$message_repo" "$message_output"
grep -F 'sensitive content: reachable commit message(s)' "$message_output" >/dev/null ||
  fail "sensitive commit message was not reported"
grep -F 'sensitive content: reachable annotated tag message(s)' "$message_output" >/dev/null ||
  fail "sensitive annotated tag message was not reported"
if grep -F "$commit_token" "$message_output" >/dev/null ||
  grep -F "$tag_token" "$message_output" >/dev/null; then
  fail "message failure disclosed matching content"
fi

large_repo="$FIXTURES/large"
new_repo "$large_repo"
printf 'clean\n' > "$large_repo/README.md"
commit_all "$large_repo" "Create large-blob fixture"
dd if=/dev/zero of="$large_repo/archive.bin" bs=1048576 count=11 2>/dev/null
commit_all "$large_repo" "Add oversized blob"
git -C "$large_repo" rm -q -- archive.bin
git -C "$large_repo" commit -q -m "Remove oversized blob"

large_output="$FIXTURES/large-output"
expect_failure "$large_repo" "$large_output"
grep -F 'oversized blob:' "$large_output" >/dev/null ||
  fail "historical oversized blob was not reported"

printf 'Someday public-history verifier tests passed.\n'
