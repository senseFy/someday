#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAB_PATH="${1:-${ANDROID_PLAY_AAB_PATH:-$ROOT_DIR/app/android/build/outputs/bundle/release/android-release.aab}}"
EXPECTED_PACKAGE_NAME="${ANDROID_PLAY_PACKAGE_NAME:-saien.someday}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command is unavailable: $1"
}

[[ -f "$AAB_PATH" ]] || die "Google Play AAB not found: $AAB_PATH"
[[ "$EXPECTED_PACKAGE_NAME" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] \
  || die "Invalid expected Android package name: $EXPECTED_PACKAGE_NAME"

require_command jarsigner
require_command perl
require_command strings
require_command unzip

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/someday-play-aab.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

SIGNATURE_REPORT="$TMP_DIR/jarsigner.txt"
jarsigner -verify -verbose "$AAB_PATH" > "$SIGNATURE_REPORT" 2>&1 || {
  cat "$SIGNATURE_REPORT" >&2
  die "AAB signature verification failed."
}
if grep -Fq 'jar is unsigned' "$SIGNATURE_REPORT" || \
  ! grep -Fq 'jar verified.' "$SIGNATURE_REPORT"; then
  die "AAB is not signed with a verifiable release certificate."
fi

MANIFEST_FILE="$TMP_DIR/AndroidManifest.xml"
unzip -p "$AAB_PATH" base/manifest/AndroidManifest.xml > "$MANIFEST_FILE" \
  || die "AAB does not contain the base AndroidManifest.xml."
MANIFEST_STRINGS="$TMP_DIR/manifest-strings.txt"
strings -a "$MANIFEST_FILE" > "$MANIFEST_STRINGS"
grep -Fq "$EXPECTED_PACKAGE_NAME" "$MANIFEST_STRINGS" \
  || die "AAB manifest does not contain expected package name: $EXPECTED_PACKAGE_NAME"

RESOURCE_LIST="$TMP_DIR/resources.txt"
unzip -Z1 "$AAB_PATH" > "$RESOURCE_LIST"

BUNDLE_CONFIG="$TMP_DIR/BundleConfig.pb"
unzip -p "$AAB_PATH" BundleConfig.pb > "$BUNDLE_CONFIG" \
  || die "AAB does not contain BundleConfig.pb."
# BundleConfig stores native-library page alignment as protobuf fields:
# BundleConfig.optimizations(2) -> uncompress_native_libraries(2) -> alignment(2).
if ! perl -0777 -e '
  use strict;
  use warnings;

  sub read_varint {
    my ($data, $position_ref) = @_;
    my $value = 0;
    my $shift = 0;
    while ($$position_ref < length($data)) {
      my $byte = ord(substr($data, $$position_ref, 1));
      $$position_ref++;
      $value |= ($byte & 0x7f) << $shift;
      return $value if ($byte & 0x80) == 0;
      $shift += 7;
      die "Invalid protobuf varint\n" if $shift > 63;
    }
    die "Truncated protobuf varint\n";
  }

  sub find_field {
    my ($data, $wanted_field, $wanted_wire_type) = @_;
    my $position = 0;
    while ($position < length($data)) {
      my $tag = read_varint($data, \$position);
      my $field = $tag >> 3;
      my $wire_type = $tag & 7;
      my $value;
      if ($wire_type == 0) {
        $value = read_varint($data, \$position);
      } elsif ($wire_type == 1) {
        die "Truncated fixed64 protobuf field\n" if $position + 8 > length($data);
        $value = substr($data, $position, 8);
        $position += 8;
      } elsif ($wire_type == 2) {
        my $length = read_varint($data, \$position);
        die "Truncated protobuf message\n" if $position + $length > length($data);
        $value = substr($data, $position, $length);
        $position += $length;
      } elsif ($wire_type == 5) {
        die "Truncated fixed32 protobuf field\n" if $position + 4 > length($data);
        $value = substr($data, $position, 4);
        $position += 4;
      } else {
        die "Unsupported protobuf wire type: $wire_type\n";
      }
      return $value if $field == $wanted_field && $wire_type == $wanted_wire_type;
    }
    return undef;
  }

  my $bundle_config = <>;
  my $optimizations = find_field($bundle_config, 2, 2);
  exit 1 unless defined $optimizations;
  my $native_libraries = find_field($optimizations, 2, 2);
  exit 1 unless defined $native_libraries;
  my $alignment = find_field($native_libraries, 2, 0);
  exit(defined($alignment) && $alignment == 2 ? 0 : 1);
' "$BUNDLE_CONFIG"; then
  die "AAB does not request 16 KB ZIP alignment for native libraries."
fi

if grep -Eq '^base/lib/(arm64-v8a|x86_64)/.+\.so$' "$RESOURCE_LIST"; then
  READELF_BIN="$(command -v llvm-readelf || command -v readelf || true)"
  if [[ -z "$READELF_BIN" && -n "${ANDROID_HOME:-}" ]]; then
    NDK_SEARCH_ROOTS=()
    [[ -d "$ANDROID_HOME/ndk" ]] && NDK_SEARCH_ROOTS+=("$ANDROID_HOME/ndk")
    [[ -d "$ANDROID_HOME/ndk-bundle" ]] && NDK_SEARCH_ROOTS+=("$ANDROID_HOME/ndk-bundle")
    if [[ ${#NDK_SEARCH_ROOTS[@]} -gt 0 ]]; then
      READELF_BIN="$(
        find "${NDK_SEARCH_ROOTS[@]}" -type f -name llvm-readelf -perm -111 2>/dev/null \
          | sort \
          | tail -1
      )"
    fi
  fi
  [[ -n "$READELF_BIN" ]] \
    || die "llvm-readelf/readelf is required to verify 16 KB ELF alignment."

  NATIVE_LIB_DIR="$TMP_DIR/native-libs"
  mkdir -p "$NATIVE_LIB_DIR"
  while IFS= read -r native_entry; do
    unzip -qq "$AAB_PATH" "$native_entry" -d "$NATIVE_LIB_DIR"
  done < <(grep -E '^base/lib/(arm64-v8a|x86_64)/.+\.so$' "$RESOURCE_LIST")

  while IFS= read -r -d '' native_lib; do
    load_segment_count=0
    while IFS= read -r alignment; do
      load_segment_count=$((load_segment_count + 1))
      if (( alignment < 0x4000 )); then
        die "Native library is not 16 KB ELF-aligned: ${native_lib#"$NATIVE_LIB_DIR"/} (LOAD alignment $alignment)"
      fi
    done < <("$READELF_BIN" -lW "$native_lib" | awk '$1 == "LOAD" { print $NF }')
    [[ "$load_segment_count" -gt 0 ]] \
      || die "Could not inspect ELF LOAD segments: ${native_lib#"$NATIVE_LIB_DIR"/}"
  done < <(find "$NATIVE_LIB_DIR" -type f -name '*.so' -print0)
fi

printf 'Google Play AAB verification passed:\n'
printf '  AAB: %s\n' "$AAB_PATH"
printf '  Package: %s\n' "$EXPECTED_PACKAGE_NAME"
printf '  Signature: verified\n'
printf '  Native page size: 16 KB compatible\n'
