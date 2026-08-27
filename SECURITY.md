# Security Policy

Someday stores personal notes and journal content. Treat security reports as
sensitive.

## Supported surfaces

Security reports are welcome for:

- Client apps (Android, iOS, Desktop)
- The self-hosted Ktor server
- Encryption, key storage, recovery material handling
- Self-hosted sync transport and conflict handling
- Authentication, session, and device management on the server

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Prefer one of:

1. GitHub **private vulnerability reporting** on this repository, if enabled.
2. Contact the repository owner through their GitHub profile contact options.

Include as much of the following as you can:

- Affected component and version / commit
- Impact (confidentiality, integrity, availability)
- Reproduction steps or a minimal proof of concept
- Whether the issue is already public elsewhere

You should receive an acknowledgment when the report is seen. Please give a
reasonable window for assessment and fix before any public disclosure.

## Secrets and local data

- Never commit API keys, `.p8` files, keystores, passwords, recovery phrases, or
  production credentials.
- Do not attach real user databases, crash logs with personal content, or device
  exports to public issues unless you have stripped personal data.
- Signing and store-publishing credentials stay outside the repository (see
  README and local environment variables).

## Scope notes

- The self-hosted server stores note and image payloads as client ciphertext;
  server bugs that break auth, account/workspace isolation, quota enforcement,
  or transport integrity are still in scope. Media quota is a per-account total
  across all workspaces.
- `compose.yaml` and the server's `local` mode are development surfaces. A
  public deployment must use the fail-closed `production` contract, HTTPS, and
  private database networking documented in
  [`docs/self-hosting.md`](docs/self-hosting.md).
- Workspace pairing uses a one-use 128-bit capability and an end-to-end
  encrypted invitation. Treat a pairing QR or manual token as a temporary
  workspace secret and report any path that logs it, makes it enumerable,
  accepts it across authorities, or permits a second claim. The protocol and
  threat model are documented in
  [`docs/workspace-pairing-protocol.md`](docs/workspace-pairing-protocol.md).
- Portable export/restore does not contain app-private image bytes. Operators
  must protect and consistently back up both PostgreSQL and the configured media
  store; disclosing either can expose sensitive metadata or ciphertext.
- Social engineering of individual users and physical device access are
  generally out of scope unless Someday mishandles them.
