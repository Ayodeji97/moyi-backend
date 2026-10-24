# ADR-0042 — Deploying by hand with Kamal, from the image CI published

**Status:** Accepted · **Date:** 2026-10-24

## Context
ADR-0007 puts everything on one Hetzner CAX21, and ADR-0011 makes kamal-proxy
the only reverse proxy. Doc 10 §4 describes the pipeline that would surround it:
merge, a multi-arch image in GHCR, a staging deploy, a manual gate, then
production. M3 needs the first real deploy before any of that pipeline exists:
two people using the API for a week. CI already publishes a multi-arch image
for every merge, but nothing ran it.

The server exists: `moyi-prod-1` (CAX21, Arm64, Ubuntu 24.04, Nuremberg), behind
a Hetzner firewall that admits 22, 80 and 443 only, with key-only SSH and
automatic security updates. `api.getmoyi.app` points straight at it.

## Decision
1. **Kamal 2 deploys from the Mac, by hand, and never builds.** Every deploy is
   `kamal deploy --skip-push --version=<a commit on main>`: the server pulls the
   image CI built and tested for that commit. Production therefore runs exactly
   what CI published, never a build from a laptop. Kamal checks that a pulled
   image carries a `service` label naming the service, so the image build sets
   one (`BP_IMAGE_LABELS=service=moyi`, app/build.gradle.kts).
2. **Secrets live in Bitwarden only.** `.kamal/secrets` holds references; Kamal
   reads the secure note `moyi-prod` through the Bitwarden CLI at deploy time.
   No secret is written to the repository or to a file on the Mac. On the server
   Kamal keeps them in an env file readable by root, which is where Docker needs
   them.
3. **Postgres 18 and Valkey 8 run as Kamal accessories** on Kamal's Docker
   network, with no published port. The app reaches them by container name.
   Postgres gets 2 GB and `shared_buffers=512MB`, `effective_cache_size=1536MB`
   (doc 10 §2.1); the app gets 2 GB.
4. **Cloudflare is DNS-only for `api.getmoyi.app`.** kamal-proxy obtains the
   certificate from Let's Encrypt, which must reach the server directly, and the
   app's trusted-proxy list stays one Docker range. `forward_headers: true`
   passes the client's address through kamal-proxy.
5. **The app's health check is `/healthz`** on the application's port, the
   actuator's `proxy` group. The actuator itself is on 8081, which kamal-proxy
   does not route and the firewall does not admit.

## Consequences
**Positive:** one command deploys, with zero downtime and `kamal rollback` back
to any earlier image. The image that runs is the image that was tested. No
deploy credential sits in GitHub. Rebuilding the server is documented and
repeatable.

**Negative:** a deploy needs the owner's Mac and master password, so nobody else
can deploy and nothing deploys on merge. The M0 definition ("deployed by CI",
doc 25) is not yet met. There is no staging: a release meets production
directly. The app connects as the database owner, so migrations and runtime
share one role. The proxy's access log records request paths and queries,
including search terms and invite codes (the plan's D9); it stays on the server,
capped at 10 MB, and is never shipped elsewhere.

**Neutral:** the trusted-proxy range (`MOYI_SECURITY_CLIENT_ADDRESS_TRUSTED_PROXIES`)
is known only once Kamal has created its network on the server, so it is set by
a change after the first deploy. Until then every client shares one rate-limit
bucket: over-strict, never spoofable (ADR-0023).

## Alternatives considered
- **Kamal builds the image on each deploy** — rejected: production would run an
  untested build from a laptop, and an Arm build on an Arm Mac would differ from
  CI's in ways no test sees.
- **A CI deploy job** — the intended end state (doc 10 §4). Deferred until the
  manual deploy is proven, so that no deploy credential is put in GitHub for a
  system that has never run.
- **Cloudflare's proxy in front of the API** — deferred to Phase 5, with an
  Origin CA certificate and Cloudflare's ranges added to the trusted proxies.
- **docker compose on the server** — no zero-downtime cutover, no rollback, and
  TLS by hand.

## Revisit when
The live week is over (then add the CI deploy), a second person needs to
deploy, or Phase 5 adds staging and Cloudflare's proxy.
