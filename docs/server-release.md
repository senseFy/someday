# Server release

This is the maintainer runbook for publishing Someday Server. Server releases
use `server-vX.Y.Z`; client releases continue to use `vX.Y.Z`.

The maintainer entry point is:

```bash
make server-release
# Or start with a prefilled version:
make server-release SERVER_RELEASE_VERSION=X.Y.Z
```

The terminal menu can show the plan, inspect status, or run the rehearsal.
Status reports `PASS`, `ACTION`, `RUNNING`, `FAIL`, or `CI` and ends with one
next action. Empty menu input selects this read-only check; rehearsal requires
typing its full confirmation. For non-interactive use, run
`make server-release-plan`, `make server-release-status`, or
`make server-release-rehearse` with `SERVER_RELEASE_VERSION=X.Y.Z`.

These entry points may inspect GitHub and run local tests or containers. They
never create a tag, push, publish, change package visibility, or run a
managed-storage gate.

The maintainer environment is macOS, Linux, or WSL with Bash, Python 3, Git,
GitHub CLI, JDK 21, Docker, and Docker Compose 2.20 or newer.

## One-time GitHub controls

Before the first server tag:

- keep `main` protected with strict `Hermetic project check`, administrator
  enforcement, linear history, and no force pushes or deletion;
- add an active tag ruleset for `server-v*` that restricts creation, updates,
  and deletion, with only release maintainers in `Always` bypass mode;
- enable private vulnerability reporting; and
- keep the default workflow token read-only.

These repository settings are maintained in GitHub, not by the release script.
The first image publication has one additional package-visibility step described
below.

## Release path

| Stage | Owner | Result |
| --- | --- | --- |
| Identity | `scripts/server-release` | Public repository, clean `main`, and unused `server-vX.Y.Z` |
| Managed storage | Maintainer | PlanetScale and R2 evidence for the same commit |
| Rehearsal | `scripts/server-release rehearse` | Source, System V3, image, and Compose checks pass locally |
| Upgrade acceptance | Maintainer | From the second release, non-empty data upgrades from the immediately preceding version |
| Trigger | Maintainer | One annotated tag is pushed with an exact refspec |
| Publication | `.github/workflows/server-release.yml` | Public AMD64/ARM64 image and GitHub Release |
| Verification | `scripts/server-release status` | Successful workflow and matching GitHub Release are visible |

## Managed evidence

Named providers are release-verified only when both files exist, contain a
passing result, and record the release commit:

```text
build/managed-storage-profile-gate/planetscale/result.json
build/managed-storage-profile-gate/r2/result.json
```

The gates reset dedicated resources, and the R2 gate writes indefinitely locked
objects. Read [Managed storage profile gates](managed-storage-profile-gates.md),
prepare disposable resources, then run each gate explicitly. A dirty worktree
can pass live checks but cannot produce release evidence.

## Rehearse

Run the complete non-publishing exercise:

```bash
make server-release-rehearse SERVER_RELEASE_VERSION=X.Y.Z
```

The rehearsal reuses the repository's existing checks and exercises both
deployment topologies with a local image. It does not claim the CI-only
AMD64/ARM64, Docker Engine 24, anonymous GHCR, or GitHub Release results.
Inspect the final summary and rerun `status`; do not tag while it reports an
action or failure.

For the second and later server releases, acceptance also requires upgrading
non-empty data from the immediately preceding version. Complete the check in
[Server upgrade and rollback](server-upgrades.md) before the final `status` and
tag. This is a manual gate; `status` does not infer it.

## Publish

Publishing begins only with an explicit annotated tag and exact refspec push:

```bash
VERSION=X.Y.Z
git tag -a "server-v$VERSION" -m "Someday Server $VERSION"
git push origin "refs/tags/server-v$VERSION:refs/tags/server-v$VERSION"
```

The tag workflow repeats the source and System V3 gates, publishes one immutable
AMD64/ARM64 image, checks anonymous pulls and both deployment topologies, then
creates the GitHub Release with the deployment bundle and checksum.

The first time the `someday-server` GHCR package is created, a maintainer must
change that package's visibility to public in GitHub while the `public-image`
job waits. The workflow does not change visibility. Later releases retain the
package setting.

Follow progress with:

```bash
make server-release-status SERVER_RELEASE_VERSION=X.Y.Z
```

`status` marks the release complete only after it reads a successful workflow
run and matching non-draft GitHub Release. Anonymous image, digest, and platform
checks run inside that workflow; `status` does not query the registry itself.

## Failure recovery

- Before pushing the tag, fix the issue, commit it, refresh commit-bound managed
  evidence, and rehearse again.
- If the image job passed and a later job failed transiently, rerun failed jobs
  only. Make the package public first when `public-image` requests it.
- If the image job failed, inspect the workflow and GHCR before retrying. An
  image tag that was already created requires a new server version.
- Never move a pushed release tag or overwrite its image. A code or artifact
  defect gets a new server version.
- Use `status` after every retry; its final line is the next maintainer action.
