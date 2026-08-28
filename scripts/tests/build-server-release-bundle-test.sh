#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
BUNDLE_SCRIPT="$ROOT_DIR/scripts/build-server-release-bundle"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/someday-server-bundle-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

VERSION=1.2.3
IMAGE="ghcr.io/sensefy/someday-server:$VERSION"
DIGEST="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
COMMIT="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
PACKAGE="someday-server-$VERSION"

fail() {
    printf 'server release bundle test error: %s\n' "$*" >&2
    exit 1
}

build_bundle() {
    local output_dir="$1"
    "$BUNDLE_SCRIPT" \
        --version "$VERSION" \
        --image "$IMAGE" \
        --digest "$DIGEST" \
        --commit "$COMMIT" \
        --output-dir "$output_dir"
}

expect_failure() {
    local label="$1"
    shift
    if "$@" >"$TEST_ROOT/$label.out" 2>&1; then
        fail "$label unexpectedly succeeded"
    fi
}

[[ -x "$BUNDLE_SCRIPT" ]] || fail "bundle script is not executable: $BUNDLE_SCRIPT"
command -v python3 >/dev/null 2>&1 || fail 'python3 is required'

first="$TEST_ROOT/first"
second="$TEST_ROOT/second"
build_bundle "$first" >/dev/null
build_bundle "$second" >/dev/null

first_archive="$first/$PACKAGE.tar.gz"
second_archive="$second/$PACKAGE.tar.gz"
first_checksum="$first/$PACKAGE.tar.gz.sha256"
second_checksum="$second/$PACKAGE.tar.gz.sha256"
cmp -s "$first_archive" "$second_archive" || fail 'two builds were not byte-identical'
cmp -s "$first_checksum" "$second_checksum" || fail 'checksum files differed between builds'

python3 -B - \
    "$first_archive" \
    "$first_checksum" \
    "$PACKAGE" \
    "$IMAGE" \
    "$DIGEST" \
    "$COMMIT" <<'PY'
import gzip
import hashlib
import pathlib
import re
import sys
import tarfile

archive_path = pathlib.Path(sys.argv[1])
checksum_path = pathlib.Path(sys.argv[2])
package, image, digest, commit = sys.argv[3:]
archive_bytes = archive_path.read_bytes()
expected_checksum = hashlib.sha256(archive_bytes).hexdigest()
expected_line = f"{expected_checksum}  {archive_path.name}\n"
if checksum_path.read_text(encoding="ascii") != expected_line:
    raise SystemExit("checksum file does not match the archive")
if archive_bytes[:3] != b"\x1f\x8b\x08":
    raise SystemExit("artifact is not a gzip stream")
if archive_bytes[3] & 0x08:
    raise SystemExit("gzip header contains an output filename")
if archive_bytes[4:8] != b"\0\0\0\0":
    raise SystemExit("gzip header mtime is not normalized")

with tarfile.open(archive_path, mode="r:gz") as archive:
    members = archive.getmembers()
    normalized_names = [member.name.rstrip("/") for member in members]
    if normalized_names != sorted(normalized_names):
        raise SystemExit("tar members are not in canonical path order")
    if len(normalized_names) != len(set(normalized_names)):
        raise SystemExit("tar contains duplicate paths")
    for member in members:
        path = pathlib.PurePosixPath(member.name)
        if path.is_absolute() or ".." in path.parts:
            raise SystemExit(f"unsafe tar member: {member.name}")
        if not (member.isdir() or member.isfile()):
            raise SystemExit(f"non-regular tar member: {member.name}")
        if member.uid != 0 or member.gid != 0 or member.uname or member.gname:
            raise SystemExit(f"identity metadata is not normalized: {member.name}")
        if member.mtime != 0:
            raise SystemExit(f"mtime is not normalized: {member.name}")
        expected_mode = 0o755 if member.isdir() or member.name.endswith("10-create-app-role.sh") else 0o644
        if member.mode != expected_mode:
            raise SystemExit(
                f"mode is not normalized: {member.name} {oct(member.mode)}"
            )

    source_overrides = [name for name in normalized_names if name.endswith("compose.source.yaml")]
    if source_overrides:
        raise SystemExit(f"source overrides leaked into the bundle: {source_overrides}")
    required = {
        f"{package}/IMAGE.txt",
        f"{package}/LICENSE",
        f"{package}/README.md",
        f"{package}/deploy/standalone/compose.yaml",
        f"{package}/deploy/standalone/.env.example",
        f"{package}/deploy/standalone/initdb/10-create-app-role.sh",
        f"{package}/deploy/external/compose.yaml",
        f"{package}/deploy/external/.env.example",
    }
    missing = required.difference(normalized_names)
    if missing:
        raise SystemExit(f"bundle is missing expected paths: {sorted(missing)}")

    expected_image_text = (
        f"Image: {image}@{digest}\n"
        f"Digest: {digest}\n"
        "Platforms: linux/amd64, linux/arm64\n"
        f"Source: {commit}\n"
    )
    image_file = archive.extractfile(f"{package}/IMAGE.txt")
    if image_file is None or image_file.read().decode("utf-8") != expected_image_text:
        raise SystemExit("IMAGE.txt has unexpected content")
    readme_file = archive.extractfile(f"{package}/README.md")
    release_version = package[len("someday-server-") :]
    expected_guide = (
        "https://github.com/senseFy/someday/blob/"
        f"server-v{release_version}/docs/self-hosting.md"
    )
    if readme_file is None or expected_guide not in readme_file.read().decode("utf-8"):
        raise SystemExit("README.md does not link the versioned deployment guide")
    expected_assignment = f"SOMEDAY_IMAGE={image}@{digest}"
    for topology in ("standalone", "external"):
        member = archive.extractfile(f"{package}/deploy/{topology}/.env.example")
        if member is None:
            raise SystemExit(f"missing {topology} environment example")
        text = member.read().decode("utf-8")
        if text.count(expected_assignment) != 1:
            raise SystemExit(f"{topology} environment image was not replaced exactly once")
        if "X.Y.Z" in text or "<release-digest>" in text:
            raise SystemExit(f"{topology} environment still contains a placeholder")
PY

original_archive_digest="$(python3 -B - "$first_archive" <<'PY'
import hashlib
import pathlib
import sys
print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
expect_failure overwrite build_bundle "$first"
after_archive_digest="$(python3 -B - "$first_archive" <<'PY'
import hashlib
import pathlib
import sys
print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
[[ "$original_archive_digest" == "$after_archive_digest" ]] ||
    fail 'overwrite attempt changed the existing archive'

common=(
    --version "$VERSION"
    --image "$IMAGE"
    --digest "$DIGEST"
    --commit "$COMMIT"
)
expect_failure missing-output "$BUNDLE_SCRIPT" "${common[@]}"
expect_failure unknown-option "$BUNDLE_SCRIPT" "${common[@]}" --wat value --output-dir "$TEST_ROOT/unknown"
expect_failure duplicate-version "$BUNDLE_SCRIPT" "${common[@]}" --version "$VERSION" --output-dir "$TEST_ROOT/duplicate"
expect_failure invalid-version "$BUNDLE_SCRIPT" \
    --version 01.2.3 --image ghcr.io/sensefy/someday-server:01.2.3 \
    --digest "$DIGEST" --commit "$COMMIT" --output-dir "$TEST_ROOT/invalid-version"
expect_failure prerelease-version "$BUNDLE_SCRIPT" \
    --version 1.2.3-rc.1 --image ghcr.io/sensefy/someday-server:1.2.3-rc.1 \
    --digest "$DIGEST" --commit "$COMMIT" --output-dir "$TEST_ROOT/prerelease"
expect_failure wrong-image "$BUNDLE_SCRIPT" "${common[@]/$IMAGE/ghcr.io/other/someday-server:$VERSION}" \
    --output-dir "$TEST_ROOT/wrong-image"
expect_failure invalid-digest "$BUNDLE_SCRIPT" \
    --version "$VERSION" --image "$IMAGE" --digest sha256:AAAA \
    --commit "$COMMIT" --output-dir "$TEST_ROOT/invalid-digest"
expect_failure invalid-commit "$BUNDLE_SCRIPT" \
    --version "$VERSION" --image "$IMAGE" --digest "$DIGEST" \
    --commit deadbeef --output-dir "$TEST_ROOT/invalid-commit"
expect_failure source-output-boundary "$BUNDLE_SCRIPT" "${common[@]}" \
    --output-dir "$ROOT_DIR/deploy/generated-release-output"
[[ ! -e "$ROOT_DIR/deploy/generated-release-output" ]] ||
    fail 'source-boundary rejection created an output directory'

mkdir -p "$TEST_ROOT/real-output"
ln -s "$TEST_ROOT/real-output" "$TEST_ROOT/output-link"
expect_failure symlink-output "$BUNDLE_SCRIPT" "${common[@]}" \
    --output-dir "$TEST_ROOT/output-link"

checksum_conflict="$TEST_ROOT/checksum-conflict"
mkdir -p "$checksum_conflict"
printf 'sentinel\n' >"$checksum_conflict/$PACKAGE.tar.gz.sha256"
expect_failure checksum-overwrite "$BUNDLE_SCRIPT" "${common[@]}" \
    --output-dir "$checksum_conflict"
[[ ! -e "$checksum_conflict/$PACKAGE.tar.gz" ]] ||
    fail 'checksum conflict still created an archive'
[[ "$(cat "$checksum_conflict/$PACKAGE.tar.gz.sha256")" == sentinel ]] ||
    fail 'checksum conflict overwrote the existing file'

fixture="$TEST_ROOT/symlink-fixture"
mkdir -p "$fixture/scripts"
cp "$BUNDLE_SCRIPT" "$fixture/scripts/build-server-release-bundle"
cp -R "$ROOT_DIR/deploy" "$fixture/deploy"
ln -s /etc/passwd "$fixture/deploy/standalone/path-must-not-escape"
expect_failure source-symlink \
    "$fixture/scripts/build-server-release-bundle" "${common[@]}" \
    --output-dir "$TEST_ROOT/symlink-result"
[[ ! -e "$TEST_ROOT/symlink-result/$PACKAGE.tar.gz" ]] ||
    fail 'source symlink rejection still created an archive'

placeholder_fixture="$TEST_ROOT/placeholder-fixture"
mkdir -p "$placeholder_fixture/scripts"
cp "$BUNDLE_SCRIPT" "$placeholder_fixture/scripts/build-server-release-bundle"
cp -R "$ROOT_DIR/deploy" "$placeholder_fixture/deploy"
python3 -B - "$placeholder_fixture/deploy/external/.env.example" <<'PY'
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
path.write_text(path.read_text(encoding="utf-8").replace("X.Y.Z", "missing"), encoding="utf-8")
PY
expect_failure missing-placeholder \
    "$placeholder_fixture/scripts/build-server-release-bundle" "${common[@]}" \
    --output-dir "$TEST_ROOT/placeholder-result"

printf 'build-server-release-bundle=passed\n'
