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

These entry points inspect GitHub and may run local tests or containers. Tag
creation, pushes, publication, package visibility, and managed-storage gates
remain separate maintainer actions.

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
| Managed storage | Maintainer | Live evidence for each provider affected by the server diff |
| Rehearsal | `scripts/server-release rehearse` | Source, System V3, image, and Compose checks pass locally |
| Upgrade acceptance | Maintainer | From the second release, non-empty data upgrades from the immediately preceding version |
| Trigger | Maintainer | One annotated tag is pushed with an exact refspec |
| Publication | `.github/workflows/server-release.yml` | Public AMD64/ARM64 image and GitHub Release |
| Verification | `scripts/server-release status` | Successful workflow and matching GitHub Release are visible |

## Managed evidence

`scripts/server-release-provider-scope` compares the release with the newest
reachable earlier `server-v*` tag. Patch releases require a live provider gate
only when that provider's server, deployment, recovery, or relevant dependency
scope changed. A major or minor server release requires both profiles. The
classification deliberately follows the Docker server artifact and its
operator contract; client-only media UI or local-storage changes do not require
an R2 server certification.

The normal classification is:

| Change | Live profile |
| --- | --- |
| PostgreSQL persistence, migrations, database wiring, or recovery | PlanetScale |
| Server S3/media adapter, external S3 deployment, or media recovery | R2 |
| Unrelated application, UI, documentation, or release-controller code | Neither |

Named providers are release-verified when each required file exists and
contains passing evidence. Evidence from an ancestor commit remains valid when
the provider-scope checker proves that no relevant files changed afterward:

```text
build/managed-storage-profile-gate/planetscale/result.json
build/managed-storage-profile-gate/r2/result.json
```

The gates reset dedicated resources, and the R2 gate writes indefinitely locked
objects. Read [Managed storage profile gates](managed-storage-profile-gates.md),
prepare disposable resources, then run each profile reported as required by
`scripts/server-release status`. A dirty worktree can pass live checks but
cannot produce release evidence.

Run a scheduled full certification at least quarterly or whenever provider
behavior is in doubt:

```bash
SOMEDAY_SERVER_RELEASE_FORCE_MANAGED=all \
  scripts/server-release status X.Y.Z
```

Then run both live gates and rerun status with the same environment variable.

## Rehearse

Run the complete non-publishing exercise:

```bash
make server-release-rehearse SERVER_RELEASE_VERSION=X.Y.Z
```

The rehearsal reuses the repository's existing checks and exercises both
deployment topologies with a local image. CI later supplies AMD64/ARM64,
Docker Engine 24, anonymous GHCR, and GitHub Release results.
Inspect the final summary and rerun `status`; do not tag while it reports an
action or failure.

For the second and later server releases, acceptance also requires upgrading
non-empty data from the immediately preceding version. Complete the check in
[Server upgrade and rollback](server-upgrades.md) before the final `status` and
tag. This is a manual gate; `status` does not infer it.

## Publish

To publish, create an annotated tag and push its exact refspec:

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
- If validation fails before an image exists because the release workflow itself
  is defective, fix the workflow on `main`, wait for main CI on that
  workflow-fix commit, then resume the unchanged protected tag:

  ```bash
  gh workflow run server-release.yml --ref main -f tag=server-vX.Y.Z
  ```

  Recovery still checks the remote annotated tag, its original commit, main
  ancestry, the unused image version, and the absence of a GitHub Release. It
  builds and tests the tagged source, not the newer workflow-control commit.
- If the image job passed and a later job failed transiently, rerun failed jobs
  only. Make the package public first when `public-image` requests it.
- If the image job failed, inspect the workflow and GHCR before retrying. An
  image tag that was already created requires a new server version.
- Never move a pushed release tag or overwrite its image. A code or artifact
  defect gets a new server version.
- Use `status` after every retry; its final line is the next maintainer action.
