# The admin console: switching it on, using it, and switching it off

ADR-0055 and `docs/superpowers/specs/2026-11-06-admin-console-a1-design.md` say why. This
file is what the owner does. The console is **off in production** until step 4 is done.

What exists in A1: sign-in with a password and an authenticator code, three roles, an
append-only audit log, and one screen (look a user up by id or exact email, metadata only).
It lives under `/admin` on the API's own host. There is no `admin.getmoyi.app` yet (see
"Routing a host of its own").

## 0. Facts to hold on to

- Admins are not users. They are rows in `admin_accounts`, created only by a SQL function run
  on the server. There is no page, endpoint or wizard that makes one.
- Sessions live in the application's memory. **A deploy signs the operator out**, and so does
  a restart. This is accepted for one operator.
- Every sign-in, refusal, sign-out and lookup is in `audit_log`. The table refuses `UPDATE`
  and `DELETE`, even from `psql`. Nothing deletes its rows until the retention slice.
- The address allowlist sees the operator's real public address because production DNS is
  "DNS only" (no Cloudflare proxy) and the only trusted proxy is kamal-proxy's network
  (`172.18.0.0/16`). **If the host is ever proxied through Cloudflare, the allowlist sees
  Cloudflare's addresses, not the operator's.** Then either keep it DNS only, or add
  Cloudflare's published ranges to `MOYI_SECURITY_CLIENT_ADDRESS_TRUSTED_PROXIES` so that
  `X-Forwarded-For` is believed through them (and only then).

## 1. Generate the TOTP key

A 32-byte key, base64, that seals every admin's TOTP secret in the database. Make it on your
own machine and put it straight in the vault; do not paste it into a chat, a file in the
repository or a shell history you share.

```
openssl rand -base64 32
```

Name it `MOYI_ADMIN_TOTP_KEY` in the vault, next to the other `MOYI_*` secrets.

Losing or changing this key makes every enrolled TOTP secret unreadable. Every admin must then
be re-invited (step 7). Back it up with the other secrets.

## 2. Find the address to allow

Open https://api.ipify.org from the network you will sign in from. Write it as a CIDR:
`203.0.113.7/32` (one IPv4 address) or `2001:db8::1/128`. A home connection's address changes,
and a change means editing the list and redeploying (it is deploy-time configuration, on
purpose). An empty list admits nobody and the app refuses to start with the console on and the
list empty.

**Never open the console to the internet.** A password and a code are the second wall, the
allowlist is the first.

## 3. Switch it on

In `config/deploy.yml`, under `servers.web` only (never the top-level `env`, never the
`media-worker` role):

```
env:
  clear:
    MOYI_ADMIN_ENABLED: "true"
    MOYI_ADMIN_ALLOWED_NETWORKS: 203.0.113.7/32      # comma-separated for several
  secret:
    - MOYI_ADMIN_TOTP_KEY
```

Make it a pull request like any other change. After the deploy, check:

- From the allowed address, `curl -i https://api.getmoyi.app/admin/login` is `200`.
- From anywhere else (a phone on mobile data), it is the API's `401`, exactly as for any path.

Switching it back off is `MOYI_ADMIN_ENABLED: "false"` and a deploy; with it off `/admin/**` is
an unknown path, and sessions are gone with the restart.

## 4. Invite yourself

On the server (the vault's ssh key; Claude cannot do this for you):

```
docker exec -i moyi-postgres psql -U moyi -d moyi -Atc \
  "SELECT admin_invite('you@example.com', 'ADMIN')"
```

It prints a one-time token (43 characters, valid 24 hours, shown once; only its hash is
stored). Roles: `SUPPORT` (user lookup), `MODERATOR`, `ADMIN`; each includes those below it.
Running it again for the same email resets that account: password, authenticator and lockout are
cleared, live sessions end, and only the newest link works. That is also the recovery path.

## 5. Enrol

Open, from the allowed address:

```
https://api.getmoyi.app/admin/enrol?token=<the token>
```

1. Scan the QR code with an authenticator app (or type the key under it).
2. Choose a password: 15 to 128 characters, not one found in a known breach.
3. Type the current six-digit code and finish. The account becomes active and the link is spent.
   Reloading the page before finishing is safe; it shows a fresh secret.
4. You are sent to the sign-in page. Enrolment does not sign you in.

An expired, used or wrong link shows one page, "This link is not valid", with no reason.

## 6. Signing in, and what to expect

Email and password, then the code. Five failures in a row (either factor) lock the account for
15 minutes; the same page appears for a locked account and a wrong password. A code is single
use: the same code twice is refused. A session lasts 30 minutes idle and 8 hours at most.

## 7. Disabling an account, and a lost phone

```
docker exec -i moyi-postgres psql -U moyi -d moyi -Atc "SELECT admin_disable('you@example.com')"
```

The account's live sessions end on their next request. For a lost phone or a forgotten
password: run `admin_invite` again for the same email (step 4) and enrol again.

## 8. Reading the audit log

```
docker exec -i moyi-postgres psql -U moyi -d moyi -c \
  "SELECT occurred_at, actor_type, action, outcome, target_type, target_id
     FROM audit_log ORDER BY occurred_at DESC LIMIT 50"
```

`ADMIN_SIGN_IN` / `REFUSED` rows with no `actor_id` are sign-ins for an email nobody holds
(the email is not recorded). The Prometheus counters are `moyi_admin_sign_in_total{outcome}` and
`moyi_admin_lookup_total`.

## Routing a host of its own

Doc 10 names `admin.getmoyi.app`. A1 does not need it. If you want it: add the host to
kamal-proxy for the web role and a DNS-only record for it (not proxied, see "Facts"). The
allowlist applies to the path, so it works the same on either host.

## What this does not do

No entry content, no moderation queue, no suspension, no job re-runs, no passkeys (ADR-0055,
questions for the owner). Those are A2 to A5.
