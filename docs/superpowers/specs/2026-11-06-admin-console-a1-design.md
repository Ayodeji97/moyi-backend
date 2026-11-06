# Admin console A1: sign-in, MFA, roles, the audit log, and user lookup

**Status:** Draft, written 2026-11-06 by gratitude-99, without the owner. Daniel asked for the
backend work to continue autonomously. Every decision below that the corpus did not already
make is marked *(builder's call)*. The questions that only the owner can answer are in §12.

**Requirements served:** FR-110 (separate auth, MFA, roles), FR-111 (user lookup without
content), and FR-116 (append-only audit log). NFR-045 and NFR-100 are honoured where this slice
touches them. T-10 is the threat (doc 09 §2).

**Corpus this builds on:** doc 05 §7 (a Spring MVC + Thymeleaf + htmx module under `/admin`,
separate auth, mandatory TOTP, an IP allowlist), doc 09 §4–§5 (`SUPPORT` < `MODERATOR` <
`ADMIN`, CSRF tokens on the session chain, Thymeleaf escaping), doc 07 (the `audit_log`
columns and its 2-year retention), doc 10 (`admin.<domain>` behind an IP allowlist), doc 15
Phase 5 weeks 1–3, and ADR-0051's operator functions (the pattern for bootstrap).

## 1. Why this slice, and why first

Phase 5 opens with the admin console (doc 15). Until it exists, every operator action is a
`psql` function run over SSH (ADR-0051 §runbook, ADR-0053's queue). That works for one
operator, but it leaves no record of who looked at what. T-10 (an admin reads entries) is
rated **Critical**, and its mitigation is this slice's machinery: an elevated role, MFA, and
an append-only audit log.

Everything else in the console sits on four things: an admin can sign in, it can prove a
second factor, its role limits what it can do, and what it did is written down. A1 builds
those four, plus one screen (FR-111 user lookup) that exercises all of them end to end. The
later screens are listed in §11 and get their own specs.

It also teaches what doc 05 §7 says it should: session auth, CSRF and server-rendered pages,
which is a different skill set from the stateless JWT API built so far.

## 2. Goals, non-goals, anti-goals

**Goals**
- An operator invited by `psql` sets a password and enrols TOTP through the browser. From
  then on, signing in needs both factors, and a session is useless without the second.
- `/admin/**` is unreachable unless the feature is switched on **and** the caller's resolved
  address is in the allowlist. Otherwise it answers exactly what any unknown path answers
  today (§4).
- Roles are enforced on the service methods, not only on URLs.
- Every admin sign-in, sign-in failure, MFA failure, sign-out, role-gated read of personal
  data, and every write is in `audit_log`. That table refuses `UPDATE` and `DELETE` at the
  database level.
- A `SUPPORT` operator can look a user up by id or email and see metadata only (FR-111).

**Non-goals (later slices, §11):** reading entry content (FR-112), the report and moderation
queue, the support inbox, suspension (FR-113, which needs domain work across identity, bond
and gratitude), job status and re-runs (FR-114), feature flags (FR-115), the data-export
disclosure of audit rows, the 2-year retention job, WebAuthn, and account recovery other than
re-invitation.

**Anti-goals: it fails even if it works when any of these happen**
- The JWT API changes behaviour in any way, gains a session, or gains a cookie.
- Any admin page or log line shows entry text, a password, a TOTP secret or code, or a
  session id.
- A partially signed-in session (password correct, TOTP not yet given) can reach anything
  except the TOTP page and sign-out.
- An admin can alter or delete an audit row through the application or through ordinary SQL.
- The console is reachable in production before the owner switches it on.

## 3. Shape: where it lives

- **Module:** `modules/admin` (it exists, and is empty). Layers as everywhere else: `domain`,
  `service`, `infra`, `web`, `api`. `app` depends on it.
- **Same deployable, its own `SecurityFilterChain`.** The chain has
  `securityMatcher("/admin/**")` and `@Order` ahead of the existing API chain. The API chain
  in `common:security` is unchanged and stays `STATELESS`. *(builder's call; the matcher is
  path-based so it works with or without the `admin.` host.)*
- **Pages:** Thymeleaf templates under `modules/admin/src/main/resources/templates/admin/`.
  htmx is **vendored** as a static file under `/admin/static/`, never loaded from a CDN, so
  the CSP can be `script-src 'self'`.
- **Host:** doc 10 names `admin.<domain>`. A1 serves under the path `/admin` on the existing
  host and port. Routing `admin.getmoyi.app` through kamal-proxy is a deploy step for the
  owner, recorded in the runbook (§10), and it is not needed for A1 to be correct.

## 4. Switching it on, and who may reach it

- `moyi.admin.enabled` defaults to **false** in every profile, including production. Off
  means no admin chain, no admin controllers, no filters and no admin web beans (the
  persistence beans, which serve no request, may exist: ruling D of the build)
  (`@ConditionalOnProperty`, plus `@ConditionalOnWebApplication`, so the media worker,
  which runs with `web-application-type: none`, can never serve it). `/admin/**` then falls
  through to the API chain like any unknown path: `401` problem without a bearer token,
  `404` problem with one. That is "indistinguishable" in the literal sense, and it needs no
  extra filter *(builder's call, resolving builder-check finding 3)*.
- `moyi.admin.allowed-networks` is a CIDR list. It is **empty by default**, and an empty list
  admits nobody. The check uses the existing `ClientAddressResolver`, so it trusts
  `X-Forwarded-For` only through the configured proxies (ADR-0023). Production's DNS is
  "DNS only" and the one trusted proxy is kamal-proxy's network (`config/deploy.yml`), so the
  resolved address is the operator's own public address. The check is a filter placed
  **first** in the admin chain, before session lookup and CSRF. A refused address gets the
  byte-identical `401` problem the API chain's entry point writes for a request without a
  token (it calls that same `ProblemAuthenticationEntryPoint`), whether or not a bearer
  token is present, and never creates a session. A test compares the two responses
  byte for byte.
- The allowlist is deploy-time configuration. Changing it means editing the env var and
  redeploying, which is acceptable for one operator. A runtime-editable allowlist is not
  built.
- Startup fails if `enabled=true` and the list is empty, or if `enabled=true` without the
  TOTP key (§6). A switched-on console that admits nobody, or that cannot read its secrets,
  is a configuration error, and a refusal to boot says so earlier than a support ticket would.
- The `local` profile switches it on for `127.0.0.1/32` and `::1/128`, with a fixed
  development TOTP key.

## 5. Admin accounts and roles

Admins are **not users**. They live in their own tables and never appear in `users`. FR-110
says the authentication is separate, and keeping the tables apart means no user-side bug can
grant admin, and no admin-side bug can touch a user's credentials.

**`admin_accounts`**: `id uuid` (v7, assigned in code), `email citext unique`,
`role text CHECK IN ('SUPPORT','MODERATOR','ADMIN')`, `password_hash text NULL`,
`credentials_changed_at timestamptz` (set on enrolment, re-invitation and disabling; §6),
`totp_secret_enc text NULL`, `totp_last_step bigint NULL`,
`status text CHECK IN ('INVITED','ACTIVE','DISABLED')`, `failed_attempts int`,
`locked_until timestamptz NULL`, `created_at`, `activated_at NULL`, `disabled_at NULL`.

**`admin_invitations`**: `id`, `admin_id` FK, `token_hash bytea unique`, `expires_at`,
`consumed_at NULL`. The token itself is never stored.

**Roles** form a hierarchy through Spring Security's `RoleHierarchy`:
`ADMIN > MODERATOR > SUPPORT`. Doc 09 §4 is the source. A1 uses only `SUPPORT` (user lookup),
but the hierarchy is wired and tested now, so later slices only add `@PreAuthorize`.

**Bootstrap is a SQL function, in the shape of ADR-0051's operator functions**
*(builder's call)*:
`admin_invite(email citext, role text) RETURNS text`. It creates the account `INVITED` (or
resets an existing account to `INVITED`, clearing its password, TOTP secret and lockout),
writes an invitation that expires in 24 hours, writes an `audit_log` row with
`actor_type='OPERATOR'`, and returns the one-time token. The token is 32 random bytes from
`gen_random_bytes`, base64url-encoded, and only its SHA-256 is stored. The owner runs it with
`docker exec -i moyi-postgres psql …`, as the runbook already does for media. There is no
"create admin" page and no first-run wizard, because either would be a way to become admin
that does not require already holding the server. `admin_disable(email)` exists for the same
reason, and it is the only way an account is disabled in A1.

## 6. Signing in: two factors, one session

**First visit, by invitation:** `GET /admin/enrol?token=…` → if the token is valid, the
operator chooses a password and is shown a fresh TOTP secret, both as an `otpauth://` URI
rendered as a QR code and as base32 text. Then they confirm with one current code. Only a
correct code makes the account `ACTIVE`, consumes the invitation, and stores the password and
the secret. An invalid, expired or used token gets one page, "This link is not valid", with
no reason given.
The fresh secret is generated on the `GET`, stored encrypted in the still-`INVITED` account's
`totp_secret_enc`, and replaced by each later `GET`, so the page can be reloaded. The `POST`
(token, password twice, code) checks it, and a wrong code consumes nothing. Enrolment does
not sign the operator in: success redirects to `/admin/login` *(builder's call)*.

**Every later sign-in** is two controller steps that build the `Authentication` explicitly.
Spring Security 7's factor authorities are used, but **scoped to the admin chain**, not
switched on globally *(builder's call, resolving builder-check findings 1, 2 and 4)*:
- `@EnableMultiFactorAuthentication` is **not** used. It publishes one global
  `AuthorizationManagerFactory` that the API chain's `authorize(anyRequest, authenticated)`
  would pick up, and a bearer request carries only `FACTOR_BEARER`, so it would break the
  JWT API. The admin chain builds its own manager with
  `AuthorizationManagerFactories.multiFactor()` requiring `FACTOR_PASSWORD` and `FACTOR_TOTP`.
  A test proves the API chain still accepts a bearer token and still refuses its absence,
  the same as before this slice.
- 7.1.2 has no TOTP factor (`FactorGrantedAuthority` has PASSWORD, OTT, WEBAUTHN and others;
  OTT is a magic link). `FACTOR_TOTP` is this slice's own authority string, granted only by
  the TOTP step below.
- Form login is **not** used. The steps are ordinary controllers, so they render HTML
  errors and run inside a service with its own transaction and audit row.

1. `POST /admin/login` (email, password). The service checks the lockout and the password
   (dummy hash for unknown emails). On success the controller changes the session id,
   stores an `Authentication` holding **only** `FACTOR_PASSWORD` and the admin's id through
   the `HttpSessionSecurityContextRepository`, and redirects to `/admin/login/totp`.
2. Every admin route except `/admin/login`, `/admin/login/totp`, `/admin/enrol`,
   `/admin/logout` and `/admin/static/**` requires both factors. A one-factor session is
   redirected to `/admin/login/totp`, and a session without a factor goes to `/admin/login`.
3. `POST /admin/login/totp` (code). On success the session id changes again, and the
   `Authentication` is replaced with one holding `FACTOR_PASSWORD`, `FACTOR_TOTP` and the
   role authority. Only now does the session carry a role.

**Passwords:** Argon2id, **through identity's existing hasher** behind a new
`identity.api.PasswordHashing` port (`hash`, `matches`, `matchesDummy`, `isBreached`)
*(builder's call: the hasher holds the memory-bounding semaphore and the production cost
(ADR-0020, #83). A second encoder would be a second copy of both, and could exhaust memory
alongside user sign-ins)*. Admin passwords need at least 15 characters (and at most 128) and
must pass the breached-password check. The 15 is the admin module's own policy, stated once
in `AdminPassword`. Unknown emails take the dummy hash, so timing does not reveal accounts
(T-18).

**TOTP** (RFC 6238): SHA-1, 6 digits, 30-second step, ±1 step of tolerance, via
`com.eatthepath:java-otp` 1.0.0 (released 2026-08, checked on Maven Central). The QR code is
drawn by `com.google.zxing:core` 3.5.4 as a server-rendered PNG `data:` URI. **Replay is
refused**: the accepted step is stored in `totp_last_step`, updated with a compare-and-set,
and a code for that step or an earlier one fails (RFC 6238 §5.2). The secret is 20 random
bytes, stored encrypted with AES-256-GCM (random 12-byte nonce per encryption, JDK
`Cipher`) under `MOYI_ADMIN_TOTP_KEY` (32 bytes, base64, held in the vault like the other
`MOYI_*` secrets). It is decrypted only to verify a code.

**Brute force:** 5 consecutive failures (password or TOTP, counted together) lock the
account for 15 minutes, in Postgres. A success resets the counter. A locked account and a
wrong password get the same page, so the lock does not confirm an email. **No per-address
Redis limiter** *(builder's call, resolving builder-check question 4)*. Only allowlisted
addresses reach these routes at all, the Redis limiter fails open by design (ADR-0023), and
it lives in a `HandlerInterceptor` that renders JSON. The allowlist plus the Postgres lockout
is the control.

**Sessions: the servlet container's own `HttpSession`, in memory** *(builder's call,
resolving builder-check finding 5. This replaces the draft's Spring Session JDBC, whose
filter wraps every request in the app, the API included)*. The API chain is `STATELESS` and
never creates a session, so only the admin routes ever do. The cost is that a deploy signs
the operator out, which is acceptable for one operator and recorded in the runbook.
- Idle timeout 30 minutes. Absolute lifetime 8 hours from the password step, stored as a
  session attribute and checked on every admin request.
- The session id changes at each step (session-fixation protection).
- Cookie `moyi-admin` (`server.servlet.session.cookie.*`): `Secure`, `HttpOnly`,
  `SameSite=Strict`, `Path=/admin`, no `Domain`. Not `__Host-`, because that prefix forces
  `Path=/`, and `/admin` shares a host with the API. `Path=/admin` keeps the browser from
  sending it to `/api`. The `local` profile drops `Secure`.
- Sign-out (`POST /admin/logout`) invalidates the session.
- Each request re-reads the account. If it is not `ACTIVE`, or its `credentials_changed_at`
  differs from the value captured at sign-in, the session is invalidated and the request is
  sent to `/admin/login`. Disabling or re-inviting an account therefore ends its live
  sessions on their next request.

## 7. Web hardening for the admin chain

- **CSRF:** Spring Security's synchronizer token on every unsafe method, rendered into each
  form, and sent by htmx from a `<meta>` tag as `X-CSRF-TOKEN`.
- **Headers** on every `/admin/**` response: `Content-Security-Policy: default-src 'none';
  script-src 'self'; style-src 'self'; img-src 'self' data:; form-action 'self';
  frame-ancestors 'none'; base-uri 'none'`, `Referrer-Policy: no-referrer`,
  `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`. The QR code is an inline
  `data:` image rendered on the server, so no script draws it.
- **Escaping:** `th:text` only, never `th:utext`. A Konsist or template-scan test fails the
  build on `th:utext` under `templates/admin/` *(builder's call)*.
- **Errors:** admin errors render an HTML page, not RFC 9457 JSON. The global API handler
  must not swallow them, and an admin `500` shows no stack trace or exception message.
- **Accessibility (NFR-100, WCAG 2.2 AA):** every input has a label, errors are associated
  with their field, focus is visible, and the layout works without colour. A1 meets this
  through plain semantic HTML, not a component library.

## 8. The audit log

**`audit_log`** (doc 07's columns, plus `outcome`): `id uuid` (v7), `occurred_at`,
`actor_type text CHECK IN ('ADMIN','OPERATOR','SYSTEM')`, `actor_id uuid NULL`,
`action text`, `target_type text NULL`, `target_id text NULL`, `justification text NULL`,
`outcome text CHECK IN ('SUCCEEDED','REFUSED','FAILED')`, `metadata jsonb`,
`ip_hash bytea NULL` (the existing `PersonalDataHasher`), and an index on
`(target_type, target_id, occurred_at)` for the FR-112 disclosure that will read it later.

**Append-only in the database:** a `BEFORE UPDATE OR DELETE` trigger raises. That holds
against the application and against ordinary `psql`, the same reasoning as ADR-0051's
evidence trigger. Retention (2 years, doc 07) will need a single sanctioned delete. It is
deferred to the retention slice, which will add a function that sets a transaction-local
setting the trigger checks, in ADR-0051's pattern. A1 states that nothing ever deletes audit
rows until then.

**What is written**, with `action` as a closed Kotlin enum (one place, read by the `CHECK`):
`ADMIN_INVITED`, `ADMIN_DISABLED` (by the SQL functions), `ADMIN_ENROLLED`,
`ADMIN_SIGN_IN` (`SUCCEEDED` after both factors, `REFUSED` for a password or TOTP failure or a
lock), `ADMIN_SIGN_OUT`, and `USER_LOOKED_UP` (with the target user id, or `REFUSED` with no
target when nothing matched). A refused sign-in for an unknown email records no email, only
`actor_id NULL` and the ip hash.

**Atomic with what it records:** an audited write commits in the same transaction as its
audit row, or neither does. A lookup's audit row is written before the result is returned, and
if that write fails the page shows an error instead of the data. Seeing personal data without
a record is the failure T-10 names.

**Where it lives** *(builder's call, resolving builder-check question 1)*: the table, the
`AuditAction` enum and the writer stay in `modules/admin`. Every audited act in A1–A5 starts
in the console or in an operator SQL function, and the SQL functions write their own rows.
If a later module must write or read audit rows (the FR-112 disclosure in the export), it
does so through an `admin.api` port, or the writer moves to a shared module then. Moving
Kotlin code never touches the merged migration.

**Never in metadata:** an email, a password, a code, a token, a session id, or the text
someone searched for. A lookup by email records the user id it found, not the email typed.

## 9. The one screen: user lookup (FR-111)

`GET /admin/users?q=…` (role `SUPPORT`). `q` is a UUID or an email, exact match only, with no
prefix or fuzzy search *(builder's call: a search box over everyone's emails is an
enumeration tool for whoever holds the console)*. The page shows:
- id, email, display name, locale, `createdAt`, email verified (yes/no), whether the account
  is locked and until when, the number of live sessions, and `tokens_invalid_before`;
- each bond the user belongs to: bond id, status, created/ended dates, the partner's user id
  (a link to their own lookup, which is audited too), never the partner's email;
- no entry text, no entry count by day, no media, no prompts answered.

Identity and bond each expose a new `api` interface for this: `UserAdminLookup` (by id, by
email) and `BondAdminLookup` (bonds of a user). Both return admin-only DTOs whose `toString`
prints only ids. They are separate from `UserDirectory` and `BondAccess` on purpose, so a
domain module can never reach admin-shaped data by accident. An ArchitectureTest rule says
only `modules/admin` may depend on them.

## 10. Operations

- Migration **V42** (`modules/admin`): `admin_accounts`, `admin_invitations`, `audit_log`
  plus its trigger, and the `admin_invite` and `admin_disable` functions. Rollback past V42 drops an unused console. No other module's data changes.
- New secret `MOYI_ADMIN_TOTP_KEY`, and new config `MOYI_ADMIN_ENABLED` and
  `MOYI_ADMIN_ALLOWED_NETWORKS`, added to `config/deploy.yml` under `servers.web` only (never
  the media worker), with `ENABLED` `"false"`. The app's own startup check (§4) is the guard.
  A1 does not ship the key into the vault, so production stays off until the owner follows
  the runbook.
- A runbook, `ops/admin/README.md`: generate the key, invite yourself, enrol, the allowlist
  (the owner's own address, and why the admin host should never be opened to the internet),
  routing `admin.getmoyi.app`, disabling an account, and what to do on a lost phone
  (re-invite).
- Metrics: `moyi_admin_sign_in_total{outcome}` and `moyi_admin_lookup_total`. A sign-in
  failure rate belongs on doc 11's security dashboard.
- Smoke: `scripts/smoke.sh` gains an admin section. It checks that `/admin` is 404 when off,
  that a refused address gets the API's unauthenticated `401` problem with no cookie (§4), runs invite → enrol → sign in with a computed code → lookup,
  and probes that a replayed code, a missing CSRF token and a one-factor session are each
  refused. It then reads `audit_log` to confirm the rows exist and that `UPDATE` and `DELETE`
  raise.

## 11. What comes after A1 (each its own spec)

A2: the report queue and quarantined media (`MODERATOR`), wrapping ADR-0051's and
ADR-0053's functions so each decision carries the operator's id. A3: suspension (FR-113, doc
04 §8.1), the largest slice, because the bond pause touches the close job and the streak.
A4: job status and re-runs (FR-114) and feature flags (FR-115). A5: content access with typed
justification (FR-112), which needs the export from Phase 5 week 4 to surface the audit rows.
Then the retention job for `audit_log`.

## 12. Questions for the owner

1. **Admin password and MFA policy.** Built as: a 15-character minimum, the breached-password
   check, TOTP only, and recovery by re-invitation from the server. Is TOTP-only acceptable
   for v1, or do you want WebAuthn/passkeys before launch? *(The answer changes A1 only if it
   is "passkeys instead of TOTP".)*
2. **Exact-match lookup only.** Built as: by id or full email, with no partial search. If
   support work needs "find by name", that is a later, separately audited feature.
3. **Is the console ever opened beyond your own address?** Built as: an allowlist that admits
   only what you configure, with no VPN or Cloudflare Access in front. If you plan to use
   Cloudflare Access, it would replace or sit in front of the allowlist, and the trusted-proxy
   CIDRs must include Cloudflare's.
