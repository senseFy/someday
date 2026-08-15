#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERIFY_SCRIPT="$ROOT_DIR/scripts/verify-play-aab.sh"
FIXTURES="$(mktemp -d "${TMPDIR:-/tmp}/someday-verify-play-aab-test.XXXXXX")"
trap 'rm -rf "$FIXTURES"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

[[ -x "$VERIFY_SCRIPT" ]] ||
  fail "AAB verifier is missing or is not executable: $VERIFY_SCRIPT"

sdk_root="$FIXTURES/sdk"
ndk_bin="$sdk_root/ndk/27.1.12297006/toolchains/llvm/prebuilt/darwin-x86_64/bin"
mkdir -p "$ndk_bin"
cat > "$ndk_bin/llvm-readobj" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$ndk_bin/llvm-readobj"
ln -s llvm-readobj "$ndk_bin/llvm-readelf"
[[ -L "$ndk_bin/llvm-readelf" ]] ||
  fail "Test fixture did not create an llvm-readelf symlink."

# A PATH-only lookup would miss the NDK tool, and find -type f would miss the
# executable symlink that the NDK actually ships.
resolved="$(
  PATH="/usr/bin:/bin" \
    ANDROID_HOME="$sdk_root" \
    ANDROID_SDK_ROOT= \
    "$VERIFY_SCRIPT" --resolve-readelf
)"
[[ "$resolved" == "$ndk_bin/llvm-readelf" ]] ||
  fail "Expected NDK llvm-readelf symlink, got: $resolved"

if PATH="/usr/bin:/bin" \
  ANDROID_HOME="$FIXTURES/empty-sdk" \
  ANDROID_SDK_ROOT= \
  "$VERIFY_SCRIPT" --resolve-readelf \
  > "$FIXTURES/missing-output" 2>&1; then
  fail "Readelf resolution succeeded without llvm-readelf or an NDK."
fi
grep -F "llvm-readelf/readelf is required" "$FIXTURES/missing-output" >/dev/null ||
  fail "Missing-readelf failure did not explain the requirement."

echo "Someday Play AAB verifier tests passed."
