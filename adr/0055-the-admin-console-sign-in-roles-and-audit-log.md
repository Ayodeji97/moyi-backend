# ADR-0055 — The admin console (A1): its own chain and session, two factors by hand, roles on the service methods, and an audit log the database will not let anyone change

**Status:** Accepted, provisional where marked · **Date:** 2026-11-06 · **Deciders:** made without
the owner; his to revisit. Daniel asked for the backend work to continue autonomously, so every
decision the corpus had not already made is the builder's, and the questions only he can answer are
at the end. It builds on doc 05 §7, doc 09 §4–§5, doc 07 and ADR-0051 (operator functions).

## Context

FR-110 (separate auth, MFA, roles), FR-111 (user lookup without content) and FR-116 (append-only
audit log) open Phase 5. T-10, an admin reading entries, is rated Critical; its mitigation is an
elevated role, a second factor and a log nobody can edit. Until now every operator action was a
`psql` function over SSH, with no record of who looked at what.

The spec (`docs/superpowers/specs/2026-11-06-admin-console-a1-design.md`) holds the detail. An
independent "builder check" of its first draft found five faults, and four of this record's
decisions exist because of them (decisions 3, 4, 6 and 7). This record is what was built and the
rulings made on the way.

What binds the design: the JWT API changes in no way, gains no session and no cookie; no page or
log line shows entry text, a password, a TOTP secret or code, or a session id; a half-signed-in
session reaches nothing but the code page and sign-out; the audit table cannot be altered; and the
console is unreachable in production until the owner switches it on.

## Decision

**1. Admins are not users.** Their own tables (`admin_accounts`, `admin_invitations`, V60), never
`users`. No user-side bug can grant admin and no admin-side bug can touch a user's credentials.

**2. Bootstrap is a SQL function, not a page.** `admin_invite(email, role)` and `admin_disable(email)`
in ADR-0051's pattern, run by whoever holds the server. A page or a first-run wizard would be a way
to become admin that does not require holding the server. Re-invitation is also the recovery path.
It resets an existing account (password, secret, lockout cleared; live sessions end), including a
disabled one.

**3. Its own `SecurityFilterChain`, `securityMatcher("/admin/**")`, ahead of the API chain, with
factor authorities scoped to it.** `@EnableMultiFactorAuthentication` is not used: it publishes one
global authorization manager factory that the API chain would pick up, and a bearer request carries
only `FACTOR_BEARER`, so it would have broken the JWT API (builder check, findings 1, 2, 4). The admin
chain builds its own `multiFactor()` manager requiring `FACTOR_PASSWORD` and `FACTOR_TOTP`;
Spring Security 7.1.2 has no TOTP factor, so `FACTOR_TOTP` is this module's own authority string,
granted only by the TOTP step. `ApiChainUnchangedTest` proves the API still accepts a bearer, still
refuses its absence, and sets no cookie.

**4. The sign-in is two controller steps, no form login.** Password, then code, each rotating the
session id; only the second carries a role. Controllers (not form login) so errors render HTML and
each step is one transaction with its audit row. Passwords are Argon2id through identity's existing
hasher behind a new `identity.api.PasswordHashing` port (one semaphore, one production cost, #83),
15 to 128 characters and not breached. TOTP is RFC 6238 (SHA-1, 6 digits, 30 s, plus or minus one
step) with replay refused by a compare-and-set of the accepted step; the secret is sealed with
AES-256-GCM under `MOYI_ADMIN_TOTP_KEY`.

**5. Switched off by default, and off means absent.** `moyi.admin.enabled` is false everywhere,
production included; with it on, startup fails without a non-empty allowlist or a valid key. Off,
`/admin/**` falls to the API chain like any unknown path (`401` without a token, `404` with one),
which is "indistinguishable" in the literal sense and needs no extra filter (builder check,
finding 3). The web beans carry `@ConditionalOnAdminConsole` (enabled, and a web application, so
the media worker can never serve it). **Ruling D:** the persistence beans (`AdminAccountRepository`,
`AuditLogRepository`, `AuditTrail`) may exist with the console off: they serve no request, the SQL
functions use the same tables, and conditional Spring Data repositories add fragility for nothing.
"No admin beans" means no routes, filters, controllers, properties or startup validation.

**6. The address allowlist is the first filter of the chain.** CIDRs, empty admits nobody, resolved
by the existing `ClientAddressResolver` (trusted proxies only, ADR-0023). A refused address gets the
byte-identical `401` the API entry point writes for a missing token, with or without a bearer, and
never a session. Deploy-time configuration only. Production's DNS is "DNS only" and the only trusted
proxy is kamal-proxy's `172.18.0.0/16`, so the resolved address is the operator's own; behind
Cloudflare it would be Cloudflare's (runbook).

**7. Sessions: the container's own `HttpSession`, in memory** (builder check, finding 5, replacing
Spring Session JDBC, whose filter wraps every request including the API's). Cookie `moyi-admin`,
`Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/admin`, no `Domain` (not `__Host-`, which forces
`Path=/`); `local` drops `Secure`. 30 minutes idle (`server.servlet.session.timeout`, stated; no
`moyi.admin` property sets it), 8 hours absolute (`moyi.admin.absolute-timeout`). Every request re-reads the account and ends the session if it is not `ACTIVE` or its
`credentials_changed_at` moved. **Cost: a deploy signs the operator out.** Accepted for one operator.

**8. No per-address Redis limiter** (builder check, question 4). Only allowlisted addresses reach
these routes, the limiter fails open by design (ADR-0023) and lives in a `HandlerInterceptor` that
renders JSON. The control is the allowlist plus a Postgres lockout: 5 failures, 15 minutes.
**Ruling E:** the count restarts after a lock; attempts while locked are not counted; only a full
two-factor sign-in resets it (otherwise a password holder could guess codes indefinitely); a refused
sign-in answers `401`, an enrolment field error `422`, an invalid link `404`; the enrolment records
the ip hash and the current token. A locked account and a wrong password get the same page.

**9. Roles on the service methods.** `ADMIN > MODERATOR > SUPPORT` through a static `RoleHierarchy`
bean; A1 uses `SUPPORT`; the admin `403` page renders for a refused role (the API's exception advice
would otherwise have answered JSON on `/admin`).

**10. The audit log lives in `modules/admin` and the database enforces it.** `audit_log` (doc 07's
columns plus `outcome`), a `BEFORE UPDATE OR DELETE` trigger that raises (and a statement-level one
for `TRUNCATE`), against the application and against `psql`. `action` is a closed enum read by a
`CHECK`. An audited write commits in the same transaction as its row; a lookup's row is written
before the result is returned, and if it fails the page shows an error instead of the data. Never in
metadata: an email, password, code, token, session id or search text. Nothing deletes audit rows until
the retention slice adds a single sanctioned delete in ADR-0051's pattern. If another module must
read or write rows, it will do so through an `admin.api` port.

**11. The lookup is exact-match, metadata only** (FR-111). By id or full email; no prefix search
(a search box over everyone's emails is an enumeration tool). Identity and bond expose
`UserAdminLookup` and `BondAdminLookup`, admin-shaped DTOs whose `toString` prints ids only,
separate from `UserDirectory` and `BondAccess`, and an ArchitectureTest rule lets only `modules/admin`
depend on them. The partner is shown by id, never email.

**12. Hardening of the pages.** CSRF synchroniser token on unsafe methods; the CSP, `Referrer-Policy`,
`nosniff` and `no-store` headers on every `/admin/**` response; `th:text` only (a test scans for
`th:utext`); htmx vendored and served from `classpath:/admin-console/` only when enabled (a static
path would leak that the console exists); admin errors render HTML with no stack trace. **Ruling F:**
the invitation token is in a hidden input of the enrolment page: it is the holder's own page, the
token is already in the URL, the page is `no-store`; the constraint on tokens targets logs, audit rows
and other people's pages. **Ruling B:** tests in `app` touch admin state only through HTTP and SQL, and
compute TOTP codes with java-otp after decrypting the stored secret with a test helper.

**13. Configuration.** `config/deploy.yml` carries `MOYI_ADMIN_ENABLED: "false"` under `servers.web`
only, a commented allowlist line, and `MOYI_ADMIN_TOTP_KEY` named in a comment, not in `secret:` (the
app refuses to start with the console on and no key, so listing a missing vault entry would break the
deploy instead). `application-local.yml` enables it for `127.0.0.1/32` and `::1/128` with a fixed,
labelled development key and `Secure` off.

## Consequences

**Positive.** An operator can be invited, can enrol, can sign in with two factors and can look a user
up, and each of those leaves a row nobody can change. The API is untouched. The console is dark in
production until the owner follows `ops/admin/README.md`.

**Negative.** A deploy ends the operator's session. The allowlist is edited by redeploying. TOTP
secrets are readable by anyone holding both the database and the key. Without the Redis limiter the
only throttle on sign-in is the lockout, which can lock a real operator out for 15 minutes by an
attacker who is already inside the allowlist and knows the email.

**Neutral.** V60 only; no other module's data changes. Metrics `moyi_admin_sign_in_total{outcome}`
and `moyi_admin_lookup_total`. `PasswordHashing`, `UserAdminLookup` and `BondAdminLookup` are new `api`
ports of identity and bond.

## Alternatives considered

- **Spring Session JDBC.** Survives a deploy; its filter wraps every request in the app, the API's
  included (decision 7).
- **`@EnableMultiFactorAuthentication`.** One global factory the API chain would pick up
  (decision 3).
- **Form login.** Errors would not be HTML and the audit row could not share the step's transaction.
- **A "create admin" page or a first-run wizard.** A way in that does not require the server.
- **A separate deployable or host.** More to run for one operator; the matcher is path-based and
  works with or without `admin.getmoyi.app`.
- **A second password hasher.** Two copies of the memory bound and the cost.
- **Fuzzy lookup.** An enumeration tool.

## Open questions (the owner's)

1. **TOTP only for v1?** Built: a 15-character minimum, the breached check, TOTP, recovery by
   re-invitation from the server. The answer changes A1 only if it is "passkeys instead of TOTP".
2. **Exact-match lookup only?** If support needs "find by name", that is a later, separately audited
   feature.
3. **Is the console ever opened beyond your own address?** Built: an allowlist that admits only what
   you configure, no VPN, no Cloudflare Access. Behind Cloudflare Access the trusted-proxy CIDRs must
   include Cloudflare's.
4. **A token holder can tell whether the console is on** (spec §4, found in the Task 4 review).
   With it off, `/admin/**` with a user's bearer token is `404`; on, from a refused address, it is
   `401`. And an invalid token's problem detail differs on admin and API paths. The spec's letter was
   followed ("answers what any unknown path answers"), which is only true of a caller with no token.
   Is that acceptable, given the caller must already hold a valid user token and be outside the
   allowlist? Making them identical means the admin refusal would have to answer `404` for a
   bearer-holder, which differs from the API's own `401` for a missing token.

## Owed

- A2 to A5 (spec §11): the report queue, suspension, jobs and flags, content access with
  justification, then the `audit_log` retention job.
- **The owner:** switch it on following `ops/admin/README.md` (key, allowlist, invite); a deploy
  hook cannot do it for him.
- Deferred minor review findings are in the build ledger, not repeated here.

## Revisit when

The owner answers any question above; a second operator exists (sessions in memory, the lockout,
re-invitation); or a screen beyond lookup is built.
