# ADR-0011 — kamal-proxy owns TLS and traffic switching; no Caddy

**Status:** Accepted · **Date:** 2026-08-30

## Context
The first architecture draft paired Kamal 2 with Caddy as the reverse proxy, on the reasoning that Caddy gives automatic TLS with trivial configuration. But Kamal 2 ships **kamal-proxy**, which is the component that performs health-checked cutover, drains the old container and terminates TLS. Running Caddy in front of it means two components both plausibly own TLS and neither clearly owns the blue/green switch — and the moment that ambiguity matters is during a rollback, at the worst possible time.

## Decision
kamal-proxy is the only reverse proxy. It terminates TLS (Let's Encrypt), routes `api`, `admin` and `staging-api` by host, and performs the health-checked blue/green cutover. Caddy is not deployed. Cloudflare sits in front for DNS, WAF and CDN.

## Consequences
**Positive:** one component, one config file, one thing to reason about during a deploy or rollback. `kamal rollback` is genuinely atomic because nothing outside Kamal's control holds routing state. One less container's memory on an already-tight 8 GB box.
**Negative:** less flexible than Caddy for anything unusual (custom headers, complex rewrites, serving static files). The admin console's IP allowlist is implemented at Cloudflare and in the application rather than in the proxy.
**Neutral:** if a genuinely Caddy-shaped need appears, Caddy can be added *behind* kamal-proxy for that specific host, which keeps ownership unambiguous.

## Alternatives considered
- **Caddy in front of kamal-proxy** — rejected for the ownership ambiguity above.
- **Caddy alone, with hand-rolled blue/green** — rejected: reimplements the thing Kamal exists to do.
- **Traefik** — rejected: more capable, more configuration, no benefit at one host.

## Revisit when
More than one application needs to be served from the box with materially different routing needs, or a requirement appears that kamal-proxy genuinely cannot express.
