## What changed

<!-- Describe the user-visible or operator-visible outcome. -->

## Why

<!-- Link the issue or explain the problem and chosen tradeoffs. -->

## Validation

<!-- List exact commands, platforms, and relevant manual checks. -->

- [ ] `make check` passes.
- [ ] I added or updated tests for changed behavior.
- [ ] Sync/crypto/protocol changes update their specification and pass `make sync-v3-gate`.
- [ ] Schema changes use SQLDelight migrations (client) or Flyway migrations (server).
- [ ] The change does not add secrets, private note data, credentials, signing material, or local operator files.
- [ ] Dependency metadata, wrapper checksums, Action SHAs, and image digests were reviewed if they changed.
