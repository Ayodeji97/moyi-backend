# The admin console: switching it on, using it, and switching it off

ADR-0055 and `docs/superpowers/specs/2026-11-06-admin-console-a1-design.md` say why. This
file is what the owner does. The console is **off in production** until step 4 is done, and is not switched on before the
legal checks ADR-0063 owes (GDPR Art. 15/23, DSA Art. 16(5)/17) are done.

What exists: sign-in with a password and an authenticator code, three roles, an append-only
audit log (A1, ADR-0055); a user lookup by id or exact email, metadata only, with a stated
purpose; for a `MODERATOR` or `ADMIN`, the report queue and the quarantined media (A2,
ADR-0063); for an `ADMIN`, reading a reported entry's words (A5, ADR-0064) and suspending an
account or lifting its suspension (A3, ADR-0066, below). The moderation steps themselves are
`ops/moderation/takedown.md`'s.
It lives under `/admin`. The owner ruled (2026-11-11, ADR-0055) that it is reached at
`admin.getmoyi.app`, DNS only, allowlisted to his own address; that host is not routed yet (see
"Routing a host of its own").

## 0. Facts to hold on to

- Admins are not users. They are rows in `admin_accounts`, created only by a SQL function run
  on the server. There is no page, endpoint or wizard that makes one.
- Sessions live in the application's memory. **A deploy signs the operator out**, and so does
  a restart. This is accepted for one operator.
- Every sign-in, refusal, sign-out, lookup, step-up, look at a queue or a held photo, and
  moderation decision is in `audit_log`. The table refuses `UPDATE`
  and `DELETE`, even from `psql`. Nothing deletes its rows until the retention slice.
- The address allowlist sees the operator's real public address because production DNS is
  "DNS only" (no Cloudflare proxy) and the only trusted proxy is kamal-proxy itself, by its
  container name (`MOYI_SECURITY_CLIENT_ADDRESS_TRUSTED_PROXY_HOSTS: kamal-proxy`, ADR-0062).
  **If the host is ever proxied through Cloudflare, the allowlist sees Cloudflare's addresses,
  not the operator's.** Then either keep it DNS only, or add
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

**Not before the trusted proxies are narrowed** (the owner's ruling 5, ADR-0055). They were
kamal-proxy's whole Docker network, `172.18.0.0/16`, so any container on it (the media worker,
Postgres, Valkey) could claim an allowlisted address through `X-Forwarded-For`. ADR-0062 narrowed
them to kamal-proxy alone, trusted by its container name; **this prerequisite is met once a
release with ADR-0062 is deployed.** Before switching on, check that it is:

```
ssh root@91.98.71.136 'docker inspect $(docker ps -q --filter label=service=moyi --filter label=role=web) \
  --format "{{range .Config.Env}}{{println .}}{{end}}"' | grep TRUSTED_PROXY
```

expect exactly `MOYI_SECURITY_CLIENT_ADDRESS_TRUSTED_PROXY_HOSTS=kamal-proxy` and no
`MOYI_SECURITY_CLIENT_ADDRESS_TRUSTED_PROXIES` line.

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

### Inviting a moderator

The same function, with the role `MODERATOR`:

```
docker exec -i moyi-postgres psql -U moyi -d moyi -Atc \
  "SELECT admin_invite('moderator@example.com', 'MODERATOR')"
```

Send the person the link of step 5 by a channel you trust; they enrol from an allowed address
(add theirs to `MOYI_ADMIN_ALLOWED_NETWORKS` first, step 2, and redeploy). A moderator sees
**Reports** and **Quarantine** and decides them as `ops/moderation/takedown.md` says; an uphold, a
block and a delete ask for a code proven in the last five minutes. Today there is one operator,
who decides alone (ADR-0063, the owner's ruling 5); a second pair of eyes on a block is revisited
when a second moderator is invited.

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
use: the same code twice is refused. A session lasts 30 minutes idle and 8 hours at most. The
idle timeout is `server.servlet.session.timeout` (30m, in `application.yml`); there is no
`moyi.admin` setting for it. The 8 hours are `moyi.admin.absolute-timeout`.

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
(the email is not recorded). A lookup's `metadata.purpose` says why it was made; a `SAFETY` or
`LEGAL` lookup carries its note as `justification`. A moderation decision's note is its
`justification`, and the module logs (`entry_report_log`, `media_moderation_log`) name the same
admin as `admin:<account id>`. The Prometheus counters are `moyi_admin_sign_in_total{outcome}`,
`moyi_admin_lookup_total`, `moyi_admin_moderation_decision_total{queue, decision, outcome}` and
`moyi_admin_media_review_total{outcome}`, and, for an `ADMIN`'s read of a reported entry's
words, `moyi_admin_content_read_total{outcome}` (`SHOWN`, `ERASED`, `NOT_OPEN`, `STALE`,
`NOT_ABOUT_WORDS`); its row is `ENTRY_TEXT_READ`, with the justification and `metadata.result`,
and a member's export never lists it while its case is open (ADR-0064). A suspension or a lift is
`moyi_admin_suspension_total{action, result}` (`action` `suspend` or `lift`; `result` `SUSPENDED`,
`ALREADY_SUSPENDED`, `LIFTED`, `NOT_SUSPENDED`, `GONE`, `NO_SUCH_ACCOUNT`); its row is
`ACCOUNT_SUSPENDED` or `ACCOUNT_UNSUSPENDED`, with the justification and `metadata.result`,
`statusBefore` and `deletionPending`, and a member's export never lists either (ADR-0066).

A member's data export lists only a lookup made at their request or for operations
(`ACCOUNT_LOOKED_UP`) and the removal of their own media from a moderation quarantine
(`MEDIA_REMOVED`), never a note; everything else is withheld (ADR-0063 decision 13).

## Suspending an account

For an `ADMIN` only (a `MODERATOR` and `SUPPORT` see "Only an ADMIN can suspend an account."). The
console is the only way: there is no SQL function and no `moyi` command (ADR-0066 decision 14).

1. **Look the person up first,** by id or exact email, with the purpose `SAFETY` and a note that
   says why (the report, the complaint). The lookup is its own audit row.
2. The result shows **Status** (`ACTIVE`, `PENDING_VERIFICATION`, `SUSPENDED since <instant>`,
   `DELETED`) and "paused" beside each bond that is. Under **Suspension**: the form **Suspend this
   account** (for `ACTIVE` or `PENDING_VERIFICATION`) or **Lift the suspension** (for `SUSPENDED`).
3. **Type a justification** (1 to 400 characters) and **a code from your authenticator app.** Each
   act needs a new code, so a suspension and its lift are two 30-second steps apart. A justification
   that is refused shows the form again and spends no code; a wrong or reused code is refused. The
   justification is kept only as the audit row's `justification`: never in the page you are sent
   to, a log line or a metric.
4. You are sent back to the lookup page (a `303`), with one line saying what happened. Do not
   resubmit the form from the browser's history: the spent code is refused and counts towards the
   five-failure lockout.

**What the member meets:** every session ends at once (refresh tokens revoked, push tokens forgotten,
access tokens refused from the next whole second); signing in answers exactly what a wrong password
answers; a reset asked for now sends nothing; a verification link records itself and leaves the
account suspended. **They are told nothing**: no email, no screen. One gap: a reset link sent
*before* the suspension still changes the password for the rest of its hour (the account stays
suspended; owed in ADR-0066).

**What the partner meets:** each bond the member is in (and has not left) shows `paused: true`, and
today gives no prompt. A new entry, an edit and a reaction are `409 BOND_PAUSED` ("This bond is
paused. Everything in it can still be read; new entries wait until it resumes."). Everything else
still works, leaving and reporting above all. No push says the bond paused or resumed, and the daily
reminder is not sent while it is paused. Days of the pause are `SUSPENDED`: no streak breaks and no
freeze is spent. An invite into the member's bond answers as a revoked one, until the lift.

**Tell nobody why.** Not the member, not the partner, not someone who asks by email. Whether the
member must be sent a statement of reasons (DSA Art. 17) is one of the legal checks the console
waits on; until it is answered, no message is sent.

**A pending deletion:** if the person had asked to delete their account, the page says "A deletion
this person asked for runs on <instant>, suspended or not." It is carried out on time; the suspension
does not stop it (ADR-0066 decision 15).

**Lifting** returns the account to `ACTIVE` (or `PENDING_VERIFICATION` if the address was never
verified) and the partner's bonds take entries again at once. **It restores no session:** the member
signs in afresh, and is not told the suspension was lifted. A reveal that fell due during the pause
stays withheld after the lift (ADR-0066 decision 17).

The same attempt twice (suspending a suspended account, lifting one that is not) changes nothing and
is still recorded, `REFUSED`.

## Routing a host of its own

Doc 10 names `admin.getmoyi.app`, and the owner ruled (2026-11-11) that the console is reached
there. To route it: add the host to kamal-proxy for the web role and a DNS-only record for it
(not proxied, see "Facts"). The allowlist applies to the path, so it works the same on either
host; the checks in step 3 then use `https://admin.getmoyi.app/admin/login`.

## What stays on the server

The console never does these; they are `scripts/moyi` or `psql` on the server, as before:

- creating, resetting and disabling an admin (`admin_invite`, `admin_disable`);
- **deleting `BLOCKED` evidence**, after the authority's report (`moyi media quarantine delete`,
  runbook §2 step 4; the owner's ruling 4 in ADR-0063);
- holding or blocking an asset that is not in the quarantine queue, by id (`moyi media quarantine
  hold|block`, for a report that came by email);
- the support inbox (`moyi support`, ADR-0057);
- the TOTP key, the allowlist and the switch itself (deploy-time configuration).

Once the console is on, an entry's words are no longer read at `psql` to decide a report (the
owner's ruling 2, runbook §3.3). They are read in the console, by an `ADMIN` only, from an open
report about words, each read with a typed justification and a new authenticator code (ADR-0064,
runbook §3.3). Read the words page once and do not reload it or go back to it: the browser posts
the spent code again, which answers `401` and counts towards the five-failure lockout.

## What this does not do

No entry content beyond that one read (A5, ADR-0064), no job re-runs, no passkeys (ADR-0055, the owner's ruling 1: TOTP only until a second operator
joins), no suspension with an end date and no appeal route (ADR-0066). Jobs are A4.
