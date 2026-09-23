# ADR-0019 — Access tokens: RS256 from a configured key, verified by Spring Security's resource server

**Status:** Accepted · **Date:** 2026-09-23

## Context

Doc 09 §3 fixes the access token: JWT, RS256, fifteen minutes, claims `sub jti iat exp iss aud scope`, no PII, revocation by `users.tokens_invalid_before`. Doc 25 D5 fixes the line between what we write and what we take: "the token lifecycle" is ours; "all crypto and protocol primitives — Spring Security's `SecurityFilterChain`, Nimbus JOSE for JWT signing and verification" are the library's. Slice E1 is the first code on either side of that line, and five things had to be decided that neither document says.

The course's answer to each is instructive because it is what most tutorials do: a hand-written `OncePerRequestFilter` calling a `JwtService` over jjwt, HS256 with a base64 secret in a properties file, no `kid`, no audience, no revocation, and a bare 401 with an empty body.

## Decision

**1. Spring Security's resource server, not a hand-written filter.** `oauth2ResourceServer { jwt { } }` with `NimbusJwtDecoder` for verification and `NimbusJwtEncoder` for issuing. `BearerTokenAuthenticationFilter` parses the header, routes failures and emits the `WWW-Authenticate` challenge; the decoder is **pinned to RS256**, which is what closes the algorithm-confusion family — `alg: none` has no signature to check, and an `HS256` token "signed" with the public key is refused before its signature is examined. The one filter Chirp writes by hand is the one whose correctness depends on details somebody has to have thought of, and the library already did.

**2. RS256 with a configured key pair, and the key fails closed.** `moyi.security.jwt.key-source` defaults to `CONFIGURED`, which requires `private-key-pem` (PKCS#8) and `public-key-pem` (X.509 SubjectPublicKeyInfo) from the environment and refuses to start without them — the same posture as the breach corpus (ADR-0016) and the email provider (ADR-0017). `EPHEMERAL` generates a 2048-bit pair at startup, logs at WARN that every token dies with the process, and is set only by the `local` profile and test contexts. Asymmetric rather than HMAC because the verifier needs only the public half: nothing that checks a token can mint one, which is what lets a second service verify tokens later without holding the signing secret. To generate a production pair:

```
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
```

Both files' contents go into the environment as the two properties; the parser tolerates the line breaks an environment variable loses, and refuses a public key that is not the private key's own — the failure that would otherwise present as every request being a 401 with nothing in any log saying why.

**3. `kid` is the RFC 7638 thumbprint of the public key.** Derived, not configured, so it cannot drift from the key it names. Rotation later is additive: a second key gets a second thumbprint, the encoder stamps the new one, the decoder is handed both, and tokens signed by either verify until the old one is withdrawn. Nothing rotates yet; the claim exists so that rotation is a configuration change and not a token-format change.

**4. Revocation is a validator on the decoder that reads `users.tokens_invalid_before`, once per authenticated request.** The port `TokenRevocations` lives in `common:security`, `identity` implements it with a two-column projection, and a token whose `iat` is before the column's value — or whose user no longer exists — is refused. Compared at **one-second resolution**, because `iat` is whole seconds: a millisecond bump would otherwise kill the token the very next login issued in the same second. Doc 07 §5's fifteen-minute Redis cache of this value is deferred to the Redis slice and must sit behind this same port, so Postgres stays the source of truth (doc 25 D6). Doc 07 §2 omitted the column that 06 and 09 both rely on; V5 adds it and the document is corrected.

**5. 401 and 403 are RFC 9457 with a `code`.** Spring Security's default for a resource server is a bare status and an empty body. Doc 06 §2 makes `code` the contract a client switches on and forbids a second error shape, so a custom `AuthenticationEntryPoint` and `AccessDeniedHandler` write the same problem body every other error uses — `UNAUTHENTICATED` and `FORBIDDEN` — keeping the RFC 6750 `WWW-Authenticate: Bearer` challenge. A `@RestControllerAdvice` catches the `AccessDeniedException` that method security raises *inside* MVC, where the catch-all advice would otherwise report a denied request as a 500. One code for every kind of bad token, deliberately: which kind is in the server log, and the client's correct response is the same for all of them.

**6. `GET /me` returns the profile only.** Doc 06 §3.2 describes "profile, bonds summary, unread state"; the last two belong to modules that do not exist and arrive as additional fields, which is not a breaking change. The caller's identity comes from the token through a `CurrentUser` argument resolver, so no controller parses a `Jwt` and none takes a user id in the path.

## Consequences

**Positive.** No hand-written crypto and no hand-written filter, which is the D5 line held. Verification needs only the public key. Revocation of all sessions genuinely revokes access tokens in flight. Every 401 the client will ever see has a `code`. A production deploy without keys is a failed deploy, not a deploy that signs everyone out at the next restart.

**Negative.** One extra Postgres read per authenticated request until the Redis slice. Two more required environment values before anything but `local` boots. The one-second comparison window means a token issued in the same second as a `logout-all` survives; this is the correct direction of error and is documented on the validator. `common:security` now carries Spring Security and the servlet API, which `tools:breach-corpus` also depends on for the Bloom filter — the CLI's classpath grows by libraries it never calls; moving the filter to its own module is the fix if that ever matters.

**Neutral.** `CurrentUser` is a plain class rather than a `value class`: Kotlin compiles a `value class` parameter to its underlying type with a mangled method name, Spring MVC then sees a `UUID`, and the resolver never matches. Found on the first run of the chain test as a 500.

## Alternatives considered

- **A hand-written `OncePerRequestFilter` over jjwt**, as Chirp does. Rejected by D5, and the concrete reason is above: the algorithm pinning, the challenge header and the failure routing are exactly the details a hand-written filter gets wrong first.
- **HS256 with a shared secret.** Rejected: every verifier holds the signing secret; any future service that checks tokens could also mint them.
- **A `jti` denylist for revocation.** Rejected as the primary mechanism: it has to remember every revoked token until its expiry and fails open on a cache flush. Doc 09 keeps it as a possible best-effort single-session optimisation, and the `jti` claim is present so that remains possible.
- **A checked-in development key pair.** Rejected: a private key in a repository is a leaked key by definition, and gitleaks would be right to fail the build. Ephemeral keys make local development work with no file at all.
- **A JWKS endpoint.** Not built: one first-party client, one verifier, and the public key is configuration. Becomes right the day a second service verifies tokens; the thumbprint `kid` is what makes that additive.

## Revisit when

A second service needs to verify tokens (publish the JWKS). The Redis slice lands (cache the revocation read behind the port). Key rotation is first needed (a second configured key, decoder handed both). Or the per-request revocation read shows up in the p95 (NFR-002), which is the same trigger as the cache.
