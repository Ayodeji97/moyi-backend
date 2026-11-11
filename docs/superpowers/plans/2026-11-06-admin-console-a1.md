# Admin console A1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A switched-off-by-default admin console under `/admin`: invited operators enrol a password and TOTP, sign in with both factors, and a `SUPPORT` operator can look a user up by id or email, every act recorded in an append-only `audit_log`.

**Architecture:** `modules/admin` holds the console. It has its own `SecurityFilterChain` (`securityMatcher("/admin/**")`, ordered before the API chain), which runs an allowlist filter first, keeps an in-memory `HttpSession`, applies CSRF, and requires `FACTOR_PASSWORD` + `FACTOR_TOTP` through an admin-scoped `AuthorizationManagerFactories.multiFactor()`. The pages are Thymeleaf with htmx vendored locally. Identity and bond each expose admin-only `api` ports. Migration V60 holds the tables, the append-only trigger, and the operator functions `admin_invite` and `admin_disable`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1.2, Spring Security 7.1.2, Thymeleaf, htmx 2 (vendored), Postgres 18, Flyway, `com.eatthepath:java-otp:1.0.0`, `com.google.zxing:core:3.5.4`, JUnit 6 + kotest assertions + MockMvc + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-06-admin-console-a1-design.md`. Read it in full before Task 1. Where this plan and the spec disagree, the spec wins: stop and report.

## Global Constraints

- `moyi.admin.enabled` defaults to `false` in every profile except `local`. When off, no admin bean exists and `/admin/**` answers as any unknown path does.
- An empty `moyi.admin.allowed-networks` admits nobody. Startup fails if `enabled=true` and either the list is empty or `moyi.admin.totp-key` is missing or is not 32 bytes of base64.
- Roles: `ADMIN > MODERATOR > SUPPORT` (Spring `RoleHierarchy`). Authorities `ROLE_SUPPORT`, `ROLE_MODERATOR`, `ROLE_ADMIN`.
- Factors: `FACTOR_PASSWORD` (Spring's `FactorGrantedAuthority.PASSWORD_AUTHORITY`) and the custom `FACTOR_TOTP`.
- Password: 15 ≤ length ≤ 128 (code points), and it must not be in the breached-password corpus.
- TOTP: SHA-1, 6 digits, 30 s step, ±1 step of tolerance. Refuse any step ≤ `totp_last_step`. Secret: 20 random bytes, AES-256-GCM with a 12-byte random nonce.
- Lockout: 5 consecutive failures → `locked_until = now + 15 min`. A success resets the count.
- Session: idle 30 min; absolute 8 h from the password step; cookie `moyi-admin`, `HttpOnly`, `Secure` (except `local`), `SameSite=Strict`, `Path=/admin`.
- Invitation token: 32 bytes from `gen_random_bytes`, base64url without padding; only `sha256(token)` is stored; it expires after 24 h.
- Never in a log, an exception message, a page or the audit metadata: a password, a TOTP secret or code, an invitation token, a session id, the email typed into the lookup, or any entry text.
- Migration version **V60**, in `modules/admin/src/main/resources/db/migration/`.
- Admin controllers never appear in `contracts/openapi.json`. `OpenApiContractTest` must pass with no change to the file.
- Commits: Conventional Commits, subject ≤ 88 characters; run `git config core.hooksPath .githooks` once in the worktree.

## Review Focus

1. **The API chain is unchanged.** A bearer request to `/api/v1/me` still succeeds, an unauthenticated one still gets the same 401 problem body, and no API response carries `Set-Cookie`. This is pinned in Task 4.
2. **A one-factor session reaches nothing.** After a correct password and before a code, every admin route except the TOTP page and sign-out redirects to `/admin/login/totp`. That includes `GET /admin/users?q=…` and an htmx request. Pinned in Task 5.
3. **TOTP clock edges and replay.** The codes for steps t-1, t and t+1 are accepted. t-2 and t+2 are refused. The same code twice is refused, and so is an earlier step's code after a later one was accepted. Two concurrent submissions of one code succeed at most once. Pinned in Task 3 (unit) and Task 5 (concurrency, through the database CAS).
4. **A refused address is indistinguishable** from no admin at all: the body and status are byte-identical to the API's unauthenticated 401, no `Set-Cookie` is sent, and the path makes no difference (`/admin/login`, `/admin/static/htmx.min.js`, `/admin/nope`). Pinned in Task 4.
5. **An email typed into the lookup in another case, or with surrounding spaces, finds the user** (citext; the input is trimmed). A malformed UUID-looking string is treated as an email and finds nothing, never a 500. Pinned in Task 6.

---

### Task 1: The admin-only ports in identity and bond

**Files:**
- Create: `modules/identity/src/main/kotlin/com/moyi/identity/api/PasswordHashing.kt`
- Create: `modules/identity/src/main/kotlin/com/moyi/identity/api/UserAdminLookup.kt`
- Create: `modules/identity/src/main/kotlin/com/moyi/identity/service/PasswordHashingAdapter.kt` (internal)
- Create: `modules/identity/src/main/kotlin/com/moyi/identity/service/UserAdminLookupService.kt` (internal)
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/api/BondAdminLookup.kt`
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/service/BondAdminLookupService.kt` (internal)
- Modify: `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt` (new rule)
- Test: `modules/identity/src/test/kotlin/com/moyi/identity/service/UserAdminLookupServiceTest.kt`, `modules/identity/src/test/kotlin/com/moyi/identity/service/PasswordHashingAdapterTest.kt`, `modules/bond/src/test/kotlin/com/moyi/bond/service/BondAdminLookupServiceTest.kt`

**Interfaces:**
- Produces:
  - `interface PasswordHashing { fun hash(raw: String): String; fun matches(raw: String, hash: String): Boolean; fun matchesDummy(raw: String): Boolean; fun isBreached(raw: String): Boolean }`. It delegates to the existing internal `PasswordHasher` and `BreachedPasswordCorpus`. `hash` wraps `raw` in `Password.of`, so any precondition `Password` enforces applies; read `Password` first. If its rules (minimum 8, NFKC, the maximum) would reject a valid 15–128 admin password, report it and stop.
  - `interface UserAdminLookup { fun byId(id: UUID): UserAdminView?; fun byEmail(email: String): UserAdminView? }`
  - `data class UserAdminView(val id: UUID, val email: String, val displayName: String, val locale: String, val createdAt: Instant, val emailVerified: Boolean, val lockedUntil: Instant?, val liveSessions: Int, val tokensInvalidBefore: Instant?)`, with `toString()` = `"UserAdminView(id=$id)"`.
  - `interface BondAdminLookup { fun bondsOf(userId: UUID): List<BondAdminView> }`
  - `data class BondAdminView(val bondId: UUID, val status: String, val createdAt: Instant, val endedAt: Instant?, val partnerUserId: UUID?)`, with `toString()` = `"BondAdminView(bondId=$bondId)"`.
  - "Live sessions" means what FR-007 lists (ADR-0025): the count of live refresh-token families. Reuse the query behind `GET /auth/sessions`; do not write a second one.

- [ ] **Step 1: Write failing tests**
  - `UserAdminLookupServiceTest`: `byEmail("  Ada@Example.COM ")` returns the user registered as `ada@example.com`; `byId(randomUUID())` and `byEmail("nobody@example.com")` return `null`; `liveSessions` is 2 after two logins and 1 after one session is deleted; `toString()` contains no email and no display name.
  - `PasswordHashingAdapterTest`: `matches(raw, hash(raw))` is true; `matches("wrong", hash(raw))` is false; `isBreached("password")` is true and `isBreached("a-long-unbreached-passphrase-42")` is false.
  - `BondAdminLookupServiceTest`: a user in one active bond and one ended bond gets two rows. The ended one has `endedAt` set. `partnerUserId` is the other member, or `null` while the bond is pending. A user in no bond gets an empty list.
- [ ] **Step 2: Run them and see them fail** (`./gradlew :modules:identity:test --tests "*AdminLookup*" --tests "*PasswordHashingAdapter*"` and `:modules:bond:test --tests "*BondAdminLookup*"`): they fail to compile.
- [ ] **Step 3: Implement the ports and the internal services.** All reads are `@Transactional(readOnly = true)`.
- [ ] **Step 4: Add the ArchitectureTest rule** `only the admin module imports the admin lookups and PasswordHashing`. Files outside `modules/admin` (and the declaring module) must not import `UserAdminLookup`, `BondAdminLookup` or `PasswordHashing`. Prove it bites: add a temporary import in `modules/gratitude`, watch the rule fail, then remove the import.
- [ ] **Step 5: Run the module tests and `./gradlew :app:test --tests "*ArchitectureTest*"`; all pass.**
- [ ] **Step 6: Commit** `feat(identity,bond): admin-only lookup and password ports`

### Task 2: V60, the audit log and the operator functions

**Files:**
- Modify: `modules/admin/build.gradle.kts` (data-jpa, web, thymeleaf, security, validation, java-otp, zxing core, common:core, common:security, common:web, identity, bond; same test deps as identity's)
- Modify: `gradle/libs.versions.toml` (`javaOtp = "1.0.0"`, `zxing = "3.5.4"`)
- Modify: `app/build.gradle.kts` (`implementation(projects.modules.admin)`)
- Create: `modules/admin/src/main/resources/db/migration/V60__admin_console.sql`
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/domain/AuditAction.kt`, `AuditOutcome.kt`, `AdminRole.kt`, `AdminStatus.kt`
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/infra/AdminAccountEntity.kt`, `AdminAccountRepository.kt`, `AuditLogEntity.kt`, `AuditLogRepository.kt`
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/AuditTrail.kt`
- Test: `modules/admin/src/test/kotlin/com/moyi/admin/infra/V60MigrationTest.kt`, `AuditActionCheckTest.kt`

**Interfaces:**
- Produces:
  - `enum class AuditAction { ADMIN_INVITED, ADMIN_DISABLED, ADMIN_ENROLLED, ADMIN_SIGN_IN, ADMIN_SIGN_OUT, USER_LOOKED_UP }`
  - `enum class AuditOutcome { SUCCEEDED, REFUSED, FAILED }`, `enum class AdminRole { SUPPORT, MODERATOR, ADMIN }`, `enum class AdminStatus { INVITED, ACTIVE, DISABLED }`
  - `internal class AuditTrail { fun record(action: AuditAction, outcome: AuditOutcome, actorId: UUID?, targetType: String?, targetId: String?, justification: String? = null, metadata: Map<String, Any> = emptyMap(), clientAddress: ClientAddress?) }`. It uses `@Transactional(propagation = MANDATORY)`, so an audit row is always part of its caller's transaction, and `actor_type = 'ADMIN'`. The ip hash comes from `PersonalDataHasher`.
  - `AdminAccountEntity` mapping every `admin_accounts` column, plus `AdminAccountRepository.findByEmail(email: String)` and `casTotpStep(id: UUID, step: Long): Int` (`UPDATE … SET totp_last_step = :step WHERE id = :id AND (totp_last_step IS NULL OR totp_last_step < :step)`).
- Schema (V60), as spec §5 and §8. `admin_accounts` with `failed_attempts int NOT NULL DEFAULT 0 CHECK (>= 0)`. `admin_invitations(admin_id → admin_accounts ON DELETE CASCADE)`. `audit_log` with the `CHECK`s and the index `(target_type, target_id, occurred_at)`. Trigger `audit_log_append_only` `BEFORE UPDATE OR DELETE … RAISE EXCEPTION 'audit_log is append-only'`, plus a `BEFORE TRUNCATE` statement trigger with the same message. `admin_invite(p_email citext, p_role text) RETURNS text` and `admin_disable(p_email citext) RETURNS void`. Each writes its `audit_log` row with `actor_type='OPERATOR'` and `actor_id NULL`. `admin_disable` on an unknown email raises `'no such admin'`. Both functions set `credentials_changed_at = now()`.

- [ ] **Step 1: Write failing tests** (`V60MigrationTest`, a `PostgresIntegrationTest` running the full Flyway chain):
  - `select admin_invite('op@example.com','SUPPORT')` returns a 43-character base64url string. The account is `INVITED`, the invitation's `token_hash = sha256(decode(token))` and expires in 24 h ± 1 min, and an `audit_log` row `ADMIN_INVITED/SUCCEEDED/OPERATOR` exists whose `metadata` does not contain the token.
  - Re-inviting an `ACTIVE` account nulls `password_hash`, `totp_secret_enc` and `totp_last_step`, resets `failed_attempts`, sets `INVITED`, and moves `credentials_changed_at` forward.
  - `admin_invite(…, 'ROOT')` raises (the role CHECK).
  - `UPDATE audit_log …`, `DELETE FROM audit_log` and `TRUNCATE audit_log` each raise `audit_log is append-only`.
  - `casTotpStep(id, 5)` returns 1, a repeated `casTotpStep(id, 5)` returns 0, and `casTotpStep(id, 4)` returns 0.
- [ ] **Step 2: Write `AuditActionCheckTest`**: the `CHECK` list on `audit_log.action`, read from `pg_constraint`, equals `AuditAction.entries` names (the rule lives once, in the enum, and the test holds the SQL to it).
- [ ] **Step 3: Run them, see them fail, implement V60 and the Kotlin, and run them again until they pass.** Then prove the trigger is the mechanism: comment out the `DELETE` arm, see the test fail, and restore it.
- [ ] **Step 4: Commit** `feat(admin): V60 admin accounts, invitations and an append-only audit log`

### Task 3: TOTP, the secret cipher, the password policy and the QR code

**Files:**
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/domain/AdminPassword.kt`
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/infra/TotpSecretCipher.kt`, `Totp.kt`, `QrCode.kt`
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/AdminProperties.kt`
- Test: `modules/admin/src/test/kotlin/com/moyi/admin/…/AdminPasswordTest.kt`, `TotpTest.kt`, `TotpSecretCipherTest.kt`, `QrCodeTest.kt`, `AdminPropertiesTest.kt`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("moyi.admin") data class AdminProperties(val enabled: Boolean = false, val allowedNetworks: List<String> = emptyList(), val totpKey: String? = null, val idleTimeout: Duration = 30.minutes, val absoluteTimeout: Duration = 8.hours, val issuer: String = "Moyi admin")`, plus `fun validate()`, which throws `IllegalStateException` naming the property, never its value.
  - `@JvmInline value class AdminPassword private constructor(val value: String)`, with `companion fun of(raw: String): AdminPassword?` (`null` outside 15..128 code points) and `toString()` = `"AdminPassword(****)"`.
  - `class TotpSecretCipher(key: ByteArray) { fun encrypt(secret: ByteArray): String; fun decrypt(stored: String): ByteArray }`. Stored form: `base64(nonce ‖ ciphertext ‖ tag)`, AES/GCM/NoPadding, 128-bit tag.
  - `class Totp(clock: Clock) { fun newSecret(): ByteArray; fun matchingStep(secret: ByteArray, code: String): Long?; fun otpauthUri(secret: ByteArray, account: String, issuer: String): String }`. `matchingStep` returns the step in {t-1, t, t+1} whose code equals `code` (constant-time compare), else `null`. Non-digit or wrong-length input gives `null`.
  - `object QrCode { fun pngDataUri(text: String): String }` (zxing `QRCodeWriter` + `MatrixToImageWriter` is in `javase`, not `core`, so render the `BitMatrix` to PNG with `javax.imageio`).

- [ ] **Step 1: Write failing tests**
  - `AdminPasswordTest`: 14 code points → `null`; 15 → ok; 129 → `null`; 15 emoji (code points, not chars) → ok; `toString` hides the value.
  - `TotpTest` with a fixed clock at a step boundary and the RFC 6238 SHA-1 vector secret `"12345678901234567890"`: at T = 59 s the code is `"287082"` (the 6-digit tail of the RFC's `94287082`) and `matchingStep` returns step 1. The codes for t-1 and t+1 match and return their own steps; t-2 and t+2 give `null`; `"28708"`, `"2870822"` and `"28708a"` give `null`. `otpauthUri` is `otpauth://totp/Moyi%20admin:op%40example.com?secret=…&issuer=Moyi%20admin&algorithm=SHA1&digits=6&period=30`.
  - `TotpSecretCipherTest`: round trip; two encryptions of one secret differ; a flipped byte throws `AEADBadTagException`; a different key cannot decrypt.
  - `QrCodeTest`: the output starts `data:image/png;base64,`, and ImageIO decodes it to an image ≥ 200 px wide.
  - `AdminPropertiesTest`: disabled with nothing set validates. Enabled with no networks fails with a message naming `moyi.admin.allowed-networks`. Enabled with a 16-byte key fails naming `moyi.admin.totp-key`, and the message does not contain the key.
- [ ] **Step 2: Run them, see them fail, implement, and run them again until they pass.**
- [ ] **Step 3: Commit** `feat(admin): TOTP with replay steps, an AES-GCM secret cipher, the password policy`

### Task 4: The admin security chain, and the proof that the API chain is untouched

**Files:**
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/web/AdminSecurityConfiguration.kt`, `AdminAddressFilter.kt`, `AdminSessionGuardFilter.kt`, `AdminConfiguration.kt` (the `@ConditionalOnProperty("moyi.admin.enabled", havingValue="true")` + `@ConditionalOnWebApplication` root that `@Import`s the rest, binds `AdminProperties` and calls `validate()`)
- Modify: `common/security/src/main/kotlin/com/moyi/common/security/SecurityConfiguration.kt`: add `@Order(Ordered.LOWEST_PRECEDENCE)` to the API chain bean only if ordering requires it, and change nothing else.
- Modify: springdoc config, if needed, so `com.moyi.admin` is not scanned (`packagesToScan`, or `@Hidden` on the admin controllers)
- Create: `modules/admin/src/main/resources/static/admin/static/htmx.min.js` (htmx 2.0.x, verified against the published SHA-384 integrity hash, recorded in a comment in `AdminSecurityConfiguration`)
- Test: `app/src/test/kotlin/com/moyi/app/admin/AdminChainTest.kt`, `AdminDisabledTest.kt`, `ApiChainUnchangedTest.kt`

**Interfaces:**
- Consumes: `AdminProperties` (Task 3), `ClientAddressResolver`, `ProblemAuthenticationEntryPoint` (common:security), `AdminAccountRepository` (Task 2).
- Produces:
  - Chain bean `adminSecurityFilterChain` with `@Order(1)`, `securityMatcher("/admin/**")`, session policy `IF_REQUIRED`, CSRF on with the token in the session, the headers of spec §7, `formLogin`, `httpBasic` and `logout` disabled, and the authorization rules of spec §6, using `AuthorizationManagerFactories.multiFactor().requireFactors(FACTOR_PASSWORD, "FACTOR_TOTP").build()`. The entry point redirects to `/admin/login`. A "missing factor" denial redirects to `/admin/login/totp`.
  - `AdminAddressFilter`, the first filter in the chain. If the address is not in the allowlist, it calls `ProblemAuthenticationEntryPoint.commence(...)` and stops. It never touches `request.getSession`.
  - `AdminSessionGuardFilter`, after the security context loads. For an authenticated admin it re-reads the account and invalidates the session if any of these holds: status ≠ `ACTIVE`, `credentials_changed_at` ≠ the session attribute `admin.credentialsChangedAt`, or now > the attribute `admin.absoluteDeadline`.
  - `const val FACTOR_TOTP = "FACTOR_TOTP"` in `com.moyi.admin.web.AdminAuthorities`.
  - A placeholder `GET /admin` controller returning a "Signed in" page, which Tasks 5 and 6 build on.

- [ ] **Step 1: Write failing tests**
  - `ApiChainUnchangedTest` (admin **enabled** in this context): a bearer-authenticated `GET /api/v1/me` is 200; with no token it is 401, and its body equals the body recorded with admin disabled (`AdminDisabledTest` writes it to a shared constant, or both compare to a literal). No API response sets a cookie. `contracts/openapi.json` is unchanged (`OpenApiContractTest` passes).
  - `AdminDisabledTest` (default properties): `GET /admin/login` without a token gives a 401 problem that equals `GET /no-such-path`'s 401 (status, content type, body), and the context contains no bean of type `AdminProperties`.
  - `AdminChainTest` (enabled, allowlist `10.9.0.0/16`, `MockMvc` with `remoteAddr`):
    - from `203.0.113.7`, `/admin/login`, `/admin/static/htmx.min.js` and `/admin/nope` each give the API's 401 problem byte for byte, with no `Set-Cookie`, and the same holds with a valid bearer token;
    - from `10.9.1.1`, `GET /admin/login` is 200 HTML carrying the CSP of spec §7 exactly, `Cache-Control: no-store`, and a `moyi-admin` cookie only once a session is created;
    - `POST /admin/login` without a CSRF token is 403 and creates no account lockout;
    - an authenticated context holding only `FACTOR_PASSWORD` that requests `GET /admin` is redirected to `/admin/login/totp`;
    - one holding both factors and `ROLE_SUPPORT` gets 200 from `GET /admin`;
    - the guard filter invalidates a session whose `admin.credentialsChangedAt` differs from the row, and one past its absolute deadline (`MutableClock`).
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.** Then prove each guard bites:
  - remove `AdminAddressFilter` and see the refused-address test fail;
  - swap `multiFactor` for `authenticated` and see the one-factor test fail;
  - restore both.
- [ ] **Step 4: Run `./gradlew :app:test`, all green.**
- [ ] **Step 5: Commit** `feat(admin): a switched-off admin chain behind an allowlist, two factors and CSRF`

### Task 5: Enrol, sign in, the second factor, sign out

**Files:**
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/AdminEnrolment.kt`, `AdminSignIn.kt`
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/web/AdminAuthController.kt`
- Create templates: `modules/admin/src/main/resources/templates/admin/layout.html`, `login.html`, `totp.html`, `enrol.html`, `enrol-invalid.html`, `home.html`, `error.html`
- Create: `modules/admin/src/main/resources/static/admin/static/admin.css`
- Test: `app/src/test/kotlin/com/moyi/app/admin/AdminSignInTest.kt`, `AdminEnrolmentTest.kt`

**Interfaces:**
- Consumes: `PasswordHashing` (Task 1); `AuditTrail`, the repositories and `casTotpStep` (Task 2); `Totp`, `TotpSecretCipher`, `AdminPassword` and `QrCode` (Task 3); the chain and `FACTOR_TOTP` (Task 4).
- Produces:
  - `AdminEnrolment.begin(token: String): EnrolmentPage?`, where `data class EnrolmentPage(val email: String, val qrDataUri: String, val secretBase32: String)` has a redacting `toString`. `null` means the token is invalid, expired or consumed.
  - `AdminEnrolment.complete(token: String, password: String, confirm: String, code: String): EnrolmentResult`, a sealed result: `Done`, `InvalidLink`, `PasswordMismatch`, `PasswordTooShortOrLong`, `PasswordBreached`, `WrongCode`.
  - `AdminSignIn.password(email: String, password: String, from: ClientAddress?): PasswordStep`, sealed: `Accepted(adminId: UUID, credentialsChangedAt: Instant)` or `Refused`. The service does the lockout bookkeeping and writes `ADMIN_SIGN_IN/REFUSED` on refusal.
  - `AdminSignIn.totp(adminId: UUID, code: String, from: ClientAddress?): TotpStep`, sealed: `Accepted(role: AdminRole)` or `Refused`. It writes `ADMIN_SIGN_IN/SUCCEEDED` or `REFUSED`.
  - `AdminSignIn.signOut(adminId: UUID, from: ClientAddress?)` writes `ADMIN_SIGN_OUT`.
  - Routes: `GET/POST /admin/enrol`, `GET/POST /admin/login`, `GET/POST /admin/login/totp`, `POST /admin/logout`, `GET /admin`. The controller changes the session id (`request.changeSessionId()`) on each accepted step, saves the context through `HttpSessionSecurityContextRepository`, and sets the session attributes `admin.credentialsChangedAt` and `admin.absoluteDeadline` (password step only).
  - Metrics (Micrometer): `moyi_admin_sign_in_total{outcome}` (`succeeded`, `refused`), incremented by `AdminSignIn` for each `ADMIN_SIGN_IN` row, and `moyi_admin_lookup_total` by `UserLookup` (Task 6). One test asserts each counter moves.
  - Copy, fixed: login refusal = "That email and password do not match an active account, or the account is locked for a few minutes."; TOTP refusal = "That code is not right. Codes change every 30 seconds."; invalid link = "This link is not valid."

- [ ] **Step 1: Write failing tests** (enabled, allowlisted `MockMvc`, real Postgres; the TOTP code is computed in the test from the stored secret via `Totp` + `TotpSecretCipher` at the `MutableClock`'s time):
  - `AdminEnrolmentTest`:
    - invite → `GET /admin/enrol?token=` shows a QR `img` and the base32 secret, and a second `GET` shows a different secret;
    - `POST` with mismatched passwords, a 14-character one or `"passwordpassword"` (breached) re-renders with the field error, and the account stays `INVITED`;
    - a wrong code consumes nothing;
    - a correct code makes the account `ACTIVE`, consumes the invitation, writes `ADMIN_ENROLLED`, sets `totp_last_step`, and redirects to `/admin/login`;
    - reusing the token gives "This link is not valid.", as do an expired token (clock + 24 h + 1 s) and a random token.
  - `AdminSignInTest`:
    - the full path: password, then code, then `GET /admin` is 200, with the session id different after each step;
    - the same code a second time (new session, same step) is refused;
    - five wrong passwords lock the account, and the right password then gets the same refusal copy; at + 15 min + 1 s it works;
    - a wrong password for an unknown email writes `REFUSED` with `actor_id IS NULL`, and its metadata contains no email;
    - **two threads submit one valid code on two sessions at once, and exactly one is accepted** (synchronise on a `CountDownLatch` before the CAS; no sleeps);
    - `admin_disable` (SQL) while signed in → the next `GET /admin` redirects to `/admin/login`;
    - sign-out invalidates the session and writes `ADMIN_SIGN_OUT`;
    - no captured log line (`OutputCaptureExtension`) contains the password, the code, the token or the secret.
- [ ] **Step 2: Run them, see them fail, implement, and run them again until they pass.** Prove the CAS bites: replace `casTotpStep` with an unconditional update, watch the concurrency test fail, and restore it.
- [ ] **Step 3: Commit** `feat(admin): invitation enrolment and two-step sign-in, audited, with lockout`

### Task 6: User lookup (FR-111)

**Files:**
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/UserLookup.kt`, `modules/admin/src/main/kotlin/com/moyi/admin/web/UserLookupController.kt`
- Create: `modules/admin/src/main/resources/templates/admin/users.html`
- Test: `app/src/test/kotlin/com/moyi/app/admin/UserLookupTest.kt`, `modules/admin/src/test/kotlin/com/moyi/admin/web/TemplateSafetyTest.kt`

**Interfaces:**
- Consumes: `UserAdminLookup`, `BondAdminLookup` (Task 1); `AuditTrail` (Task 2); the chain (Task 4).
- Produces: `@PreAuthorize("hasRole('SUPPORT')") @Transactional fun UserLookup.find(query: String, actorId: UUID, from: ClientAddress?): LookupResult`, sealed: `Found(user: UserAdminView, bonds: List<BondAdminView>)` or `NotFound`. A query parsing as a UUID is looked up by id, and anything else by email after `trim()`. The audit row (`USER_LOOKED_UP`, `SUCCEEDED` with `target_type='USER'` and `target_id`, or `REFUSED` with no target) is written in the same transaction, before the result is returned. `GET /admin/users?q=` renders the spec §9 fields. The partner id links to `/admin/users?q=<id>`.

- [ ] **Step 1: Write failing tests**
  - `UserLookupTest`:
    - `SUPPORT`, `MODERATOR` and `ADMIN` sessions all get 200, through the role hierarchy;
    - a session without a role (password factor only) is redirected to the TOTP page;
    - a lookup by id and a lookup by `"  ADA@example.com "` both show the user;
    - the page contains no entry text, even though the user wrote an entry with a unique marker string, and does not contain the partner's email;
    - `"not-a-uuid-or-email"` shows "No account matches." and writes `REFUSED`, whose metadata does not contain the query;
    - every lookup writes exactly one audit row;
    - when the audit insert fails (a test-only trigger on `audit_log` that raises), the page is the error page and does not contain the user's email.
  - `TemplateSafetyTest`: no file under `templates/admin/` contains `th:utext` or an inline `<script>` with a body, or `on…=` attributes (CSP would block them anyway, so they would be dead code). Prove the test bites with a temporary `th:utext`.
- [ ] **Step 2: Run them, see them fail, implement, and run them again until they pass.**
- [ ] **Step 3: Commit** `feat(admin): user lookup by id or email, metadata only, audited (FR-111)`

### Task 7: Operations, the record and the smoke run

**Files:**
- Modify: `app/src/main/resources/application-local.yml` (`moyi.admin.enabled: true`, `allowed-networks: [127.0.0.1/32, ::1/128]`, a fixed dev `totp-key`, `server.servlet.session.cookie.secure: false`)
- Modify: `app/src/main/resources/application.yml` (the session cookie settings of spec §6, `server.servlet.session.timeout: 30m`)
- Modify: `config/deploy.yml` (`servers.web` env: `MOYI_ADMIN_ENABLED: "false"`, and a commented `MOYI_ADMIN_ALLOWED_NETWORKS`; `MOYI_ADMIN_TOTP_KEY` named in a comment, not in `secret:`, until the owner switches it on)
- Create: `ops/admin/README.md` (spec §10's runbook)
- Create: `adr/0055-the-admin-console-sign-in-roles-and-audit-log.md` (the decisions marked "builder's call" in the spec, and each builder-check resolution)
- Modify: `scripts/smoke.sh` (an admin section, spec §10; it computes TOTP with `oathtool` if present, otherwise it skips the section and says so)
- Modify: `docs/learning-log.md` (one entry: what the builder check caught in the first draft)
- Modify: `README.md` (the module diagram line for admin)

- [ ] **Step 1: Run `./gradlew build`.** Expected: BUILD SUCCESSFUL, with test, ktlint, detekt and the architecture rules all green. Record the test count from the JUnit XML.
- [ ] **Step 2: Run the smoke script in an isolated compose project** (handover's `moyi-0f` recipe with its own ports; never another session's containers). Expected: the summary ends `0 failed`, and the admin section shows invite → enrol → sign in → lookup passing, plus replay, no CSRF and one factor each refused, and `UPDATE`/`DELETE audit_log` raising.
- [ ] **Step 3: Run `scripts/state-map-check --strict`; it is clean.** If it reports the `/admin` routes, follow its message (admin pages are not API operations).
- [ ] **Step 4: Commit** `docs(admin): ADR-0055, the admin runbook, and the smoke section`
