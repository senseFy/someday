#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
CONTRACT_SCRIPT="$ROOT_DIR/scripts/verify-server-release-contract"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/someday-server-contract-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
BASE_FIXTURE="$TEST_ROOT/base"

fail() {
    printf 'server release contract test error: %s\n' "$*" >&2
    exit 1
}

expect_failure() {
    local label="$1"
    shift
    if "$@" >"$TEST_ROOT/$label.out" 2>&1; then
        fail "$label unexpectedly succeeded"
    fi
}

run_contract() {
    local repository="$1"
    local version="$2"
    "$repository/scripts/verify-server-release-contract" \
        "server-v$version" \
        "ghcr.io/sensefy/someday-server:$version" \
        "$DIGEST"
}

new_case() {
    local label="$1"
    local repository="$TEST_ROOT/$label"
    cp -R "$BASE_FIXTURE" "$repository"
    printf '%s\n' "$repository"
}

mutate_once() {
    local path="$1"
    local before="$2"
    local after="$3"
    python3 -B - "$path" "$before" "$after" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
before, after = sys.argv[2:]
text = path.read_text(encoding="utf-8")
count = text.count(before)
if count != 1:
    raise SystemExit(f"expected exactly one mutation target in {path}, found {count}")
path.write_text(text.replace(before, after, 1), encoding="utf-8")
PY
}

move_step_after() {
    local path="$1"
    local step="$2"
    local destination="$3"
    python3 -B - "$path" "$step" "$destination" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
step, destination = sys.argv[2:]
lines = path.read_text(encoding="utf-8").splitlines(keepends=True)

def bounds(name, content):
    marker = f"      - name: {name}\n"
    matches = [index for index, line in enumerate(content) if line == marker]
    if len(matches) != 1:
        raise SystemExit(f"expected one workflow step named {name!r}, found {len(matches)}")
    start = matches[0]
    end = next(
        (index for index in range(start + 1, len(content)) if content[index].startswith("      - ")),
        len(content),
    )
    return start, end

start, end = bounds(step, lines)
block = lines[start:end]
remaining = lines[:start] + lines[end:]
destination_start, destination_end = bounds(destination, remaining)
remaining[destination_end:destination_end] = block
path.write_text("".join(remaining), encoding="utf-8")
PY
}

[[ -x "$CONTRACT_SCRIPT" ]] || fail "contract verifier is unavailable: $CONTRACT_SCRIPT"
command -v python3 >/dev/null 2>&1 || fail 'python3 is required'

mkdir -p "$BASE_FIXTURE/scripts" "$BASE_FIXTURE/.github/workflows"
cp "$CONTRACT_SCRIPT" "$BASE_FIXTURE/scripts/verify-server-release-contract"
cp "$ROOT_DIR/scripts/verify-public-history" "$BASE_FIXTURE/scripts/verify-public-history"
cp "$ROOT_DIR/scripts/build-server-release-bundle" \
    "$BASE_FIXTURE/scripts/build-server-release-bundle"
cp "$ROOT_DIR/scripts/verify-server-release-tag" \
    "$BASE_FIXTURE/scripts/verify-server-release-tag"
cp "$ROOT_DIR/.github/workflows/android.yml" "$BASE_FIXTURE/.github/workflows/android.yml"
cp "$ROOT_DIR/.github/workflows/ci.yml" "$BASE_FIXTURE/.github/workflows/ci.yml"
cp "$ROOT_DIR/.github/workflows/server-release.yml" \
    "$BASE_FIXTURE/.github/workflows/server-release.yml"
cp "$ROOT_DIR/Dockerfile" "$ROOT_DIR/LICENSE" "$BASE_FIXTURE/"
cp -R "$ROOT_DIR/deploy" "$BASE_FIXTURE/deploy"
chmod 0755 \
    "$BASE_FIXTURE/scripts/verify-server-release-contract" \
    "$BASE_FIXTURE/scripts/verify-public-history" \
    "$BASE_FIXTURE/scripts/build-server-release-bundle" \
    "$BASE_FIXTURE/scripts/verify-server-release-tag"

run_contract "$BASE_FIXTURE" 1.2.3 >/dev/null

linux_history_case="$(new_case linux-history-commented)"
mutate_once \
    "$linux_history_case/.github/workflows/ci.yml" \
    '        run: ./scripts/verify-public-history' \
    $'        run: |\n          # ./scripts/verify-public-history'
expect_failure linux-history-commented run_contract "$linux_history_case" 1.2.3
grep -Fq 'Linux CI must actively verify public history' \
    "$TEST_ROOT/linux-history-commented.out" ||
    fail 'commented Linux history gate failed for the wrong reason'

tolerated_linux_job_case="$(new_case linux-job-tolerated)"
mutate_once \
    "$tolerated_linux_job_case/.github/workflows/ci.yml" \
    $'  check:\n    name: Hermetic project check' \
    $'  check:\n    name: Hermetic project check\n    continue-on-error: true'
expect_failure linux-job-tolerated run_contract "$tolerated_linux_job_case" 1.2.3
grep -Fq 'Linux CI protected check must run every step unconditionally and fail closed' \
    "$TEST_ROOT/linux-job-tolerated.out" ||
    fail 'tolerated protected CI job failed for the wrong reason'

quality_history_case="$(new_case quality-history-commented)"
mutate_once \
    "$quality_history_case/.github/workflows/server-release.yml" \
    '        run: ./scripts/verify-public-history' \
    $'        run: |\n          # ./scripts/verify-public-history'
expect_failure quality-history-commented run_contract "$quality_history_case" 1.2.3
grep -Fq 'server release quality gates must actively verify public history' \
    "$TEST_ROOT/quality-history-commented.out" ||
    fail 'commented release history gate failed for the wrong reason'

main_ancestry_case="$(new_case main-ancestry-commented)"
mutate_once \
    "$main_ancestry_case/.github/workflows/server-release.yml" \
    '          if ! git merge-base --is-ancestor "$release_commit" "$main_commit"; then' \
    '          # if ! git merge-base --is-ancestor "$release_commit" "$main_commit"; then'
expect_failure main-ancestry-commented run_contract "$main_ancestry_case" 1.2.3
grep -Fq 'must require the release commit in origin/main history' \
    "$TEST_ROOT/main-ancestry-commented.out" ||
    fail 'commented main-ancestry gate failed for the wrong reason'

annotated_tag_case="$(new_case annotated-tag-commented)"
mutate_once \
    "$annotated_tag_case/.github/workflows/server-release.yml" \
    '            ./scripts/verify-server-release-tag "$tag" "$release_commit"' \
    '            # ./scripts/verify-server-release-tag "$tag" "$release_commit"'
expect_failure annotated-tag-commented run_contract "$annotated_tag_case" 1.2.3
grep -Fq 'must verify the protected remote tag identity' \
    "$TEST_ROOT/annotated-tag-commented.out" ||
    fail 'commented annotated-tag gate failed for the wrong reason'

pre_publish_tag_case="$(new_case pre-publish-tag-commented)"
mutate_once \
    "$pre_publish_tag_case/.github/workflows/server-release.yml" \
    $'      - name: Reconfirm the protected release tag before publishing\n        env:\n          GH_TOKEN: ${{ github.token }}\n          RELEASE_COMMIT: ${{ needs.validate.outputs.commit }}\n          RELEASE_TAG: ${{ needs.validate.outputs.tag }}\n        run: |\n          GITHUB_REF_NAME="$RELEASE_TAG" GITHUB_REF_PROTECTED=true \\\n            ./scripts/verify-server-release-tag "$RELEASE_TAG" "$RELEASE_COMMIT"' \
    $'      - name: Reconfirm the protected release tag before publishing\n        env:\n          GH_TOKEN: ${{ github.token }}\n          RELEASE_COMMIT: ${{ needs.validate.outputs.commit }}\n          RELEASE_TAG: ${{ needs.validate.outputs.tag }}\n        run: "# tag verification removed"'
expect_failure pre-publish-tag-commented \
    run_contract "$pre_publish_tag_case" 1.2.3
grep -Fq 'image publication must actively reconfirm the protected tag' \
    "$TEST_ROOT/pre-publish-tag-commented.out" ||
    fail 'commented pre-publication tag gate failed for the wrong reason'

conditional_tag_case="$(new_case pre-publish-tag-conditional)"
mutate_once \
    "$conditional_tag_case/.github/workflows/server-release.yml" \
    '      - name: Reconfirm the protected release tag before publishing' \
    $'      - name: Reconfirm the protected release tag before publishing\n        if: false'
expect_failure pre-publish-tag-conditional \
    run_contract "$conditional_tag_case" 1.2.3
grep -Fq 'image publication must run every step unconditionally and fail closed' \
    "$TEST_ROOT/pre-publish-tag-conditional.out" ||
    fail 'conditional pre-publication tag gate failed for the wrong reason'

tolerated_history_case="$(new_case quality-history-tolerated)"
mutate_once \
    "$tolerated_history_case/.github/workflows/server-release.yml" \
    '      - name: Verify public history' \
    $'      - name: Verify public history\n        continue-on-error: true'
expect_failure quality-history-tolerated \
    run_contract "$tolerated_history_case" 1.2.3
grep -Fq 'server release quality gates must run every step unconditionally and fail closed' \
    "$TEST_ROOT/quality-history-tolerated.out" ||
    fail 'tolerated public-history gate failed for the wrong reason'

tolerated_release_job_case="$(new_case release-job-tolerated)"
mutate_once \
    "$tolerated_release_job_case/.github/workflows/server-release.yml" \
    $'  github-release:\n    name: Publish GitHub Release' \
    $'  github-release:\n    name: Publish GitHub Release\n    continue-on-error: true'
expect_failure release-job-tolerated \
    run_contract "$tolerated_release_job_case" 1.2.3
grep -Fq 'GitHub Release publication must run every step unconditionally and fail closed' \
    "$TEST_ROOT/release-job-tolerated.out" ||
    fail 'tolerated GitHub Release job failed for the wrong reason'

release_dependency_case="$(new_case release-dependency-removed)"
mutate_once \
    "$release_dependency_case/.github/workflows/server-release.yml" \
    '      - runtime-smoke' \
    '      # runtime-smoke removed'
expect_failure release-dependency-removed \
    run_contract "$release_dependency_case" 1.2.3
grep -Fq 'GitHub Release must depend on runtime-smoke' \
    "$TEST_ROOT/release-dependency-removed.out" ||
    fail 'removed release dependency failed for the wrong reason'

release_order_case="$(new_case release-tag-order)"
move_step_after \
    "$release_order_case/.github/workflows/server-release.yml" \
    'Reconfirm that the tag still identifies the tested commit' \
    'Create the GitHub Release after every gate'
expect_failure release-tag-order run_contract "$release_order_case" 1.2.3
grep -Fq 'protected tag reconfirmation must run before the GitHub Release is created' \
    "$TEST_ROOT/release-tag-order.out" ||
    fail 'misordered final tag gate failed for the wrong reason'

release_verify_tag_case="$(new_case release-verify-tag-removed)"
mutate_once \
    "$release_verify_tag_case/.github/workflows/server-release.yml" \
    '            --verify-tag \' \
    '            # --verify-tag removed'
expect_failure release-verify-tag-removed \
    run_contract "$release_verify_tag_case" 1.2.3
grep -Fq 'GitHub Release command is missing: --verify-tag' \
    "$TEST_ROOT/release-verify-tag-removed.out" ||
    fail 'removed release tag verification failed for the wrong reason'

release_wrong_tag_case="$(new_case release-wrong-tag)"
mutate_once \
    "$release_wrong_tag_case/.github/workflows/server-release.yml" \
    '          gh release create "$RELEASE_TAG" \' \
    '          gh release create "server-v0.0.0" \'
expect_failure release-wrong-tag run_contract "$release_wrong_tag_case" 1.2.3
grep -Fq 'GitHub Release command is missing: gh release create "$RELEASE_TAG"' \
    "$TEST_ROOT/release-wrong-tag.out" ||
    fail 'wrong GitHub Release tag failed for the wrong reason'

bundle_case="$(new_case bundle-builder-commented)"
mutate_once \
    "$bundle_case/.github/workflows/server-release.yml" \
    '          ./scripts/build-server-release-bundle \' \
    '          # ./scripts/build-server-release-bundle \'
expect_failure bundle-builder-commented run_contract "$bundle_case" 1.2.3
grep -Fq 'must actively use the deterministic bundle builder' \
    "$TEST_ROOT/bundle-builder-commented.out" ||
    fail 'commented bundle builder failed for the wrong reason'

expect_failure leading-zero run_contract "$BASE_FIXTURE" 01.2.3
grep -Fq 'tag must match server-vX.Y.Z' "$TEST_ROOT/leading-zero.out" ||
    fail 'leading-zero version failed for the wrong reason'

version64="1.1.1$(printf '%059d' 0)"
version65="1.1.1$(printf '%060d' 0)"
[[ "${#version64}" -eq 64 ]] || fail '64-character fixture has the wrong length'
[[ "${#version65}" -eq 65 ]] || fail '65-character fixture has the wrong length'
run_contract "$BASE_FIXTURE" "$version64" >/dev/null
expect_failure version-too-long run_contract "$BASE_FIXTURE" "$version65"
grep -Fq 'version is too long' "$TEST_ROOT/version-too-long.out" ||
    fail 'overlong version failed for the wrong reason'

printf 'verify-server-release-contract=passed\n'
