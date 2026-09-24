# ADR-0026 — The bond module, its access guard, and the first cross-module port

**Status:** Accepted · **Date:** 2026-09-24 · **Deciders:** Daniel

## Context

Phase 2 (`15` §3) opens the second domain module and the first aggregate that is not a
person. Three things had to be decided that the corpus states as intentions rather than
as mechanisms, plus a handful of numbers and contradictions the design session
(`docs/superpowers/specs/2026-09-24-bonds-and-invites-design.md`) surfaced.

**How the Bond aggregate is persisted.** ADR-0003 fixed the shape — a generic `Bond` with
`Member`s and a `type` discriminator, not a `Couple` with two columns — and `04` §2 assigns
it invariants (I-1: at most `maxMembers` active members; I-5: an archived bond accepts no
writes). Nothing says whether those live in a JPA object graph, a service, or the domain
model.

**How doc 05 §5.5's three authorisation layers become code a build can check.** The document
names them — method security, a `BondAccessGuard` consulted by every bond-scoped service
method, and repository-level scoping — and `12` §3.6 asks for a Konsist rule that "every
`@RestController` method taking a `bondId` passes through `BondAccessGuard`". A rule phrased
over controllers is hard to check statically: a controller calls a service, and whether the
guard ran is a fact about the call graph, which Konsist cannot see.

**How a module learns anything about a user.** FR-002 forbids an unverified account from
creating or joining a bond, and every bond response shows its members' display names. Both
facts live in identity. `25` §6 says each module exposes "a narrow `api` package" and that no
foreign key crosses a module boundary, but no module had ever needed another one's data, so
the pattern existed only on paper.

## Decision

**1. Flat JPA entities, hand-written mappers, one store per aggregate.** `BondEntity`,
`BondMemberEntity` and `BondInviteEntity` carry plain `uuid` columns and no `@ManyToOne`;
`BondStore` assembles a `Bond` with its members in two queries it can see, and the mappers
are ordinary functions. The invariants are `require`d in the `Bond` constructor, so an
aggregate that breaks one cannot exist in memory — not checked by a service that somebody
could route around.

**2. `Membership` is a proof, and only `BondAccessGuard` can mint one.** Every bond-scoped
service function takes a `Membership` rather than a `BondId`. A service that needs one
cannot be reached by a controller that skipped the guard, because nothing else can produce
the argument: the authorisation check stops being a step someone can forget and becomes a
value they must be holding. Two Konsist rules hold the halves the type system cannot — that
no file but `BondAccessGuard.kt` constructs a `Membership`, and that no `bond.service`
function takes a `BondId` without one. That is `12` §3.6's requirement, enforced one layer
down from where it was written, because one layer down is where it is checkable.

The cross-tenant suite reads its list of `{bondId}` routes off Spring's
`RequestMappingHandlerMapping` rather than a hand-kept list, so a route added without a
fixture fails the build — `12` §3.3's "a new endpoint that is not covered fails the build",
literally. It asserts that a stranger, a random id and a value that is not an id receive
byte-identical 404s apart from `instance`.

**3. `identity.api.UserDirectory`.** An interface and a `UserSummary` DTO — id, display name,
whether the address is verified — public in `com.moyi.identity.api`; the implementation stays
`internal` in `identity.service`. Bond depends on the identity module and can reach exactly
this, because Kotlin's `internal` makes it the compiler's guarantee. `bond_members.user_id`,
`bonds.created_by` and `blocks.*_user_id` are bare `uuid`s with no foreign key. This is the
pattern for every later inter-module call.

**4. Read access survives leaving (OQ-09, confirmed by building it).** `BondStore.findByMember`
admits a member whose `left_at` is set, and the guard returns a `Membership` flagged `left`.
`states.md` §9 promises each member keeps read access to their own archive; the write paths
(B3–B5) are what refuse them.

**5. Numbers chosen here, not found in the corpus.** A bond name is 1–60 characters and a
nickname at most 40 — the same status as the 80-character display name, and flagged for Daniel
in the same way. `reminderTimezone` defaults to the bond's anchor zone when the client sends
none, because a client that does not know the device's zone is better served by the bond's than
by a fixed default.

**6. Contradictions resolved.**

- **The invite alphabet is 30 symbols**, `states.md` §2's decision of 2026-09-03:
  `23456789ABCDEFGHJKMNPQRSTVWXYZ`, giving 30⁶ ≈ 7.3 × 10⁸ and ~29.4 bits. `09` T-06 says
  "32-symbol alphabet (~10⁹)" and is the stale number; it is amended. `states.md` §2's
  **UNRESOLVED** note is closed by this ADR. The size of the space is not what makes brute
  force impractical in any case — `06` §4's per-IP limit on lookup and accept is, and that
  lands with slice B2.
- **`POST /bonds` accepts `reminderTimezone`**, which `06` §3.3 does not list. Without it a
  creator's reminders are anchored to the bond's zone until they visit settings, which for a
  Lagos–Manchester couple is the wrong zone for one of them from day one.
- **`GET /bonds` returns bonds and members now**; `06` §3.3 also promises streak and today's
  status, which arrive in Phase 3 as additional fields on the same response.

**7. A known cost, stated rather than discovered.** A bond-scoped `GET` loads the bond twice —
once in the guard, once in the service. The alternative is for the guard to hand the loaded
aggregate to the service, which couples every bond-scoped service to how the guard works. Two
queries on an indexed primary key is a price worth paying for that independence *until it is
measured otherwise*; `08`'s p95 budget on real hardware is what decides.

**8. An invite-code collision is a failed request, not a retry loop.** `bond_invites.code` is
unique across every invite ever issued, live or spent. At 7.3 × 10⁸ codes and a table that
stays small, a collision is a 500 for one request and a fresh code for the next. Scoping
uniqueness to live rows would make collisions rarer and would let two bonds answer to one code
as invites expire, which is worse in the only way that matters.

## Consequences

**Positive.** The authorisation model is structural rather than procedural: the common way to
introduce an IDOR — adding an endpoint and forgetting the check — fails the build twice over,
once at the Konsist rule and once at the cross-tenant suite. The aggregate's invariants are
enforced where they are stated. The module boundary is held by the compiler, so the second
module does not quietly become the first module's friend. The `Bond` domain model has no
Spring, no JPA and no HTTP in it, and its tests need none of those to run.

**Negative.** Two loads per bond-scoped read, as §7 says. The `Membership` rule is enforced by
matching the simple type name, so a parameter written as `com.moyi.bond.domain.BondId` evades
it — found while verifying the rule with a deliberate violation, and recorded in the rule's own
comment; it is the same narrow gap the layer rule already documents for imports. The bond
module's tests supply the identity port as a fake, so the real adapter is exercised only in
identity's own tests and in `app` — a mismatch between them would surface at the composition
root rather than in either module. And this ADR adds a third place where a display name can
appear (the port's DTO), which is a place personal data has to be kept out of logs; the DTO's
`toString` prints only the id, and the same discipline is now owed by everything that consumes
it.

**Neutral.** V9 carries three deltas from `07` §2 — `nickname_for_other` (ADR-0003's rename),
`created_by_member_id` (`04`'s own name; `07` said `created_by`), and `bonds.version`, which is
the ETag `If-Match` will compare against in B4. The invite `code` column carries the alphabet as
a CHECK constraint, so a code outside it cannot reach the table by any path.

## Alternatives considered

**A JPA object graph — `@OneToMany` members on `BondEntity`.** Fewer lines, and the aggregate
loads itself. Rejected: a Kotlin entity with a lazy collection is the trap `25` §5 names, the
collection decides for itself when to query, and `copy()` on a managed entity is the
lost-update this codebase has already documented once. *Becomes right if* the aggregate grows
large enough that hand-assembly is a real maintenance cost, which for two members it is not.

**`@PreAuthorize` SpEL for the guard.** The framework-idiomatic answer, and what an interviewer
will ask about. Rejected on two grounds: the expression is a string, so a typo is a runtime
failure rather than a compile error; and it returns a boolean, while the bond case needs the
*membership* — the caller's member id and whether they have left — which the service then has to
fetch again anyway.

**A `Membership` argument resolver, resolved from the path like `CurrentUser`.** Tempting: the
guard would run in the framework rather than in the first line of each method. Rejected because
springdoc would then document `Membership` as a request parameter, and `contracts` cannot name a
type internal to another module in its ignore list — the generated client would be told to send
it. The `CurrentUser` and `ClientContext` exclusions in `OpenApiConfiguration` are exactly this
problem, already paid for once.

**Spring Modulith's `ApplicationModules.verify()`.** The mainstream 2026 answer to module
boundaries in a Spring monolith, and worth knowing by name. Not adopted: Konsist already
encodes finer rules here — layers *within* a module, `internal` outside `api`, entity placement —
and Kotlin's `internal` gives compile-time enforcement that Modulith's runtime verification
does not. *Becomes right if* the module graph grows enough to want Modulith's documentation
and event-publication support, at which point the two coexist.

## Revisit when

- p95 of `GET /bonds/{bondId}` on the Hetzner box exceeds the `08` budget — §7's double load is
  the first thing to remove.
- A third module needs the `validate`/`reject` constraint helpers now duplicated in
  `identity.web` and `bond.web`; two is a coincidence, three is a `common:web` class.
- The `bond_invites.code` unique constraint is ever hit in production, which would mean the
  code space or the generator is not what this ADR assumes.
