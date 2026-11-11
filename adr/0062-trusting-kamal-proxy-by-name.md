# ADR-0062 — Trusting kamal-proxy by name, not the Docker network it shares

**Status:** Accepted · **Date:** 2026-11-11 · **Deciders:** Daniel (the owner's ruling 5 on #131,
recorded in ADR-0055) · **Amends:** ADR-0023 decision 6, ADR-0042's neutral consequence

## Context

ADR-0023 decision 6 believes `X-Forwarded-For` only from the proxies listed in
`moyi.security.client-address.trusted-proxies`, as CIDR ranges, and walks the header from the
right past them. Production listed `172.18.0.0/16` (#82): Kamal's whole `kamal` Docker network,
read with `docker network inspect kamal` on 2026-10-24. kamal-proxy is on that network, and so is
every other container Kamal runs: the media worker (ADR-0052), Postgres and Valkey. Each of them
was therefore a trusted proxy and could name any client it liked. The media worker is the
container that decodes what strangers upload (ADR-0048), so it is the one most likely ever to
run code it was not meant to.

Two things believe the resolved address: the per-IP rate limits (ADR-0023) and, once switched
on, the admin console's address allowlist (ADR-0055). The owner ruled (ADR-0055, ruling 5) that
the console is not switched on until only kamal-proxy is trusted.

The difficulty: kamal-proxy's address on `kamal` is Docker's to assign. Kamal 2.12 starts it with
`docker run --name kamal-proxy --network kamal` and no address (`Kamal::Commands::Proxy#run`), and
creates the network with `docker network create kamal` and no subnet
(`Kamal::Commands::Docker#create_network`). Measured locally on Docker 29.5.2: on such a network
a container that is removed gives up its address, and the next container started takes it at
once (`kamal-proxy-probe` at `.2` removed, the next `docker run` was given `.2`, and the
recreated probe `.4`). So `172.18.0.x/32` written into `config/deploy.yml` would be right until
the next `kamal proxy reboot`, and then could be some other container's.

## Decision

**1. kamal-proxy is trusted by its container name.** A new list,
`moyi.security.client-address.trusted-proxy-hosts`, holds names; `TrustedProxyHosts` resolves
each through the system's name service (on `kamal`, Docker's embedded DNS, which answers a
container's name with its current address) and trusts each address it is given, exactly: a
`/32` or `/128`, never a range. Production sets
`MOYI_SECURITY_CLIENT_ADDRESS_TRUSTED_PROXY_HOSTS: kamal-proxy` under `servers.web` and no
longer sets `MOYI_SECURITY_CLIENT_ADDRESS_TRUSTED_PROXIES` at all. A hop is trusted when it
matches either list, so a fixed range (Cloudflare's, should the host ever be proxied) still goes
in the CIDR list. The walk itself (ADR-0023 decision 6) is unchanged.

**2. Resolved again every five seconds, off the waiting path.** The answer is kept with the time
it was read. The first request that finds it older than five seconds looks the name up again;
every request meanwhile uses the previous answer instead of queueing behind the lookup, so a slow
DNS answer delays one request, not all of them. Beneath this the JDK keeps its own cache (the
production image sets no `networkaddress.cache.ttl`, so a positive answer is kept 30 s and a
negative one 10 s), so a kamal-proxy recreated at a new address is believed within about 35 s.

**3. Fails closed, and does not keep a stale answer.** A name that does not resolve contributes
no address, and the addresses it resolved to before are dropped: decision 1's measurement is that
an address given up is handed to the next container. Until the name resolves again, a request
through kamal-proxy resolves to kamal-proxy's own address. That is ADR-0023's "over-strict and
visible" posture: every client in one rate-limit bucket, and the console refusing everyone.
"Does not resolve" is as the JDK sees it: its 30 s positive cache can go on answering with the
address of a proxy that has been removed, so a removed proxy's old address, like a recreated
one's, stays trusted for up to about 35 s (Negative, below). Blank names (a stray comma in the
environment variable) are dropped before any lookup, because the JDK resolves `""` to loopback. A WARN
is written once when a name stops resolving and an INFO once when it resolves again.

**4. Only configuration is ever resolved.** The names come from deploy-time configuration. The
header's entries are still parsed as literals and never looked up (ADR-0023); nothing a client
sends reaches the name service.

**5. Nothing is looked up where no name is configured.** `local` and the tests trust loopback by
range as before (`scripts/smoke.sh` drives the per-IP buckets through it), and the media worker,
which serves no request, is not given the name.

## Consequences

**Positive:** no container but kamal-proxy can name a client, so the media worker, Postgres and
Valkey cannot step around a per-IP limit or the console's allowlist. The admin console's switch-on
prerequisite (ruling 5) is met once this is deployed. Nothing on the server changes: no network
is recreated, no proxy rebooted, no address read and copied into the repository, and a rebuilt
server needs nothing re-read (ADR-0042's neutral consequence no longer applies).

**Negative:** a DNS lookup now sits in the request path, once per five seconds, on one request.
After `kamal proxy reboot` gives kamal-proxy a new address, or after it is stopped, there is a window of up to about 35 s
in which (a) its requests are not believed, so all clients share one bucket and the console
refuses, and (b) its old address is still trusted, so a container started in that window and
given the old address would be believed. (b) needs a container to start during a proxy reboot,
which the owner does by hand and rarely; it is a window of seconds, where the `/16` was always
open. While no `kamal-proxy` container exists, Docker's embedded DNS forwards the name upstream
(and through any `search` domain the host passes on), so for that time a resolver outside the
host could answer it. The deploy check confirms the web container's `/etc/resolv.conf` carries no
`search` line; an upstream that lies about `kamal-proxy` is accepted as out of reach.

**Rollback:** `kamal rollback` runs the old image with the environment of the checkout it is run
from. From main after this change no range comes back (the old image then trusts nobody:
over-strict, all clients in one bucket). From a checkout before it, `172.18.0.0/16` comes back,
which once the console is on reopens the forgery; roll back from main.

**Neutral:** IPv6 is handled as IPv4 is: whatever addresses the name has, each trusted exactly.
`kamal` has no IPv6 today.

## Alternatives considered

- **Pin kamal-proxy to a fixed address** (`proxy.run.options.ip`, which Kamal passes to
  `docker run`). Docker 29 accepted an `--ip` inside the automatically chosen subnet when tried
  locally, but production's Docker version was not checked, `kamal proxy reboot` with an option
  Docker refuses leaves the API without its proxy (an outage), the address would have to be
  chosen from a subnet read off the server, and an unpinned container can still take the address
  while kamal-proxy is down. Rejected: more server steps, a worse failure, and the same window.
- **A dedicated network for kamal-proxy and the API only**, with a configured subnet. Kamal 2.12
  hard-codes `--network kamal` for the proxy, and recreating `kamal` with a subnet means stopping
  every container on it, Postgres included. Rejected as an outage to buy what a name already
  gives.
- **Trust the `/16` minus the other containers.** Every new container would have to be added to
  the exclusions, and forgetting one fails open. Rejected.
- **A secret header from kamal-proxy.** kamal-proxy has no option to add a request header.
- **Look the name up on every request.** Simplest, but a slow DNS answer would hold every request
  (the JDK serialises lookups of one name). Decision 2 keeps the lookup to one request per
  interval.
- **Keep the last good answer when a lookup fails.** Kinder during a DNS blip, but decision 1's
  measurement is exactly why a stale address must not be trusted. Rejected for decision 3.

## Revisit when

- The host is proxied through Cloudflare: add Cloudflare's published ranges to the CIDR list.
- Kamal gains a way to give kamal-proxy a fixed address or a network of its own.
- The 35 s window matters (a proxy that restarts often): set `networkaddress.cache.ttl` lower in
  the image, or resolve on a background schedule.
