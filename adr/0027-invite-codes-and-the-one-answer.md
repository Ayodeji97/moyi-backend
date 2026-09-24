# ADR-0027 — Invite codes, and the one answer

**Status:** Accepted · **Date:** 2026-09-25 · **Deciders:** Daniel

## Context

Slice B2 builds the four endpoints that let two accounts pair: issue a code, revoke it,
resolve it, join with it. It is milestone **M2** — *"two accounts can pair, and no third
account can see anything"* (`15` §3).

The requirements pull in two directions, and the whole design is where the line between
them falls.

**FR-024 wants one answer.** *"Joining a full, expired, revoked or non-existent Bond MUST
fail with a generic message that does not disclose the Bond's existence or members."*
`states.md` §2 puts it as *"one screen, one string, four causes"* and gives the reason:
"any visible difference between these leaks information". T-06 rates brute-forcing
6-character codes at Critical impact.

**Doc 06 §3.3 wants several.** Its table specifies `410` for an expired or used invite,
`409` for a full bond, and a `valid` boolean on the resolve response. Those cannot all
coexist with FR-024, and one of the two documents is wrong.

And a third force the corpus states but does not connect to invites: **doc 26 §2.1**
requires that *"blocking must be indistinguishable from leaving, from the other side"*,
because telling a blocked person they were identified as a threat is the moment
retaliation risk peaks (T-09).

## Decision

**1. Six causes, one response.** Expired, revoked, already used, the bond is full, the
code never existed, and the two accounts have blocked each other all return **`404` with
`code: INVITE_NOT_USABLE`** and a body that is byte-identical across all six, `instance`
aside — `instance` being the path the caller typed, which is theirs. `InviteOneAnswerTest`
builds all six states genuinely and asserts the bytes.

Doc 06 §3.3's `410`, its `409 full` on accept and its `valid` field are **superseded**. A
`410 Gone` says the code was once real; a distinct answer for a full bond says the bond
exists and somebody got there first. Both are the disclosure FR-024 forbids, to precisely
the person it is written about.

**2. The caller's own state stays specific.** `403 EMAIL_NOT_VERIFIED` (FR-002), `409
ALREADY_MEMBER`, `409 BOND_LIMIT_REACHED` (FR-025). These are facts about the caller's own
account, which they can already discover by other means, and collapsing them into the 404
would make the product unusable for the ordinary mistakes — the creator scanning their own
code is a real case, and "that code cannot be used" would be a lie.

The **order** of the checks is therefore part of the contract, and `AcceptInvite`'s KDoc
states it: verification, then the code, then the caller's membership and limit, then the
bond's room and the blocks, then the compare-and-set. Nothing below a refusal has run, so
a stranger guessing codes learns only what the earliest failing check tells them — and the
earliest ones are about themselves.

**3. Resolve checks blocks too.** This was a correction made during the slice. The first
version applied only the code-and-bond checks on the reasoning that a preview previews and
`accept` refuses. `InviteOneAnswerTest` caught what that costs: a blocked person would see
the bond's name and the inviter's, and *then* be refused — which tells them the code is
real and that something is wrong with them specifically. That is a block oracle pointed at
the one person doc 26 §2.1 says must learn nothing, and it is a worse leak than the one the
split was protecting.

**4. One live invite per bond**, and creating one revokes the outstanding one in the same
transaction (doc 06 §3.3, `states.md` §2 "Replaced") — **under the bond's row lock**. Two
concurrent creates each revoke what their own snapshot can see and each insert a new one,
leaving a bond with two live codes; found by `InviteRaceTest`, and the same shape as the
refresh-token family race on PR #32.

**5. A compare-and-set, not a read-then-write.** `bond_invites.consume` flips `used_at`
only if the row is still live, and reports whether it was this caller who spent it. Two
people presenting one code both read it as live; the database serialises the two `UPDATE`s
and the second finds the predicate false. The failure the CAS prevents is a third person in
a two-person conversation.

The row lock and the CAS are both here and neither is redundant: the lock serialises the
*whole decision* (the limit, the seat, the blocks), while the CAS is the guarantee that
survives a future caller who forgets to take the lock.

**6. The resolve endpoint shows a name, and that is safe.** `GET /invites/{code}` returns
the bond's name, its type and the inviter's display name. Doc 06 calls an *unauthenticated*
version of this an oracle that "returned a real person's name on a hit" — and the fix it
specifies is authentication, which is what this endpoint requires. The caller holds a live
code *and* an account, and the per-IP bucket bounds the guessing. The screen exists because
an invite is single-use: accepting is destructive, so a mistyped-but-valid code would
silently bond someone to a stranger with the real invite spent (`states.md` §2).

**7. Three buckets, and the per-IP one is shared.** `invite:create` 10/day per user;
`invite:lookup` 10/hour per user; and `invite:code` **20/hour per IP, named by both the
resolve and the accept endpoints** — which is what doc 06 §4's "combined" means, and what
makes 30⁶ ≈ 7.3 × 10⁸ codes safe in practice rather than in principle. An attacker able to
spend twenty guesses on each endpoint would have forty. `@RateLimited` grew a vararg and
learned per-user subjects to carry this.

**8. A malformed code is `422`, not the 404.** Six characters from a known alphabet is the
shape of the input, not a fact about any bond, so the client can put the message under the
field. `InviteCode.parse` upper-cases and trims first: a pasted or retyped lowercase code
is not a failure (`states.md` §2).

## Consequences

**Positive.** The disclosure surface of the pairing flow is one sentence. A brute-force
attempt learns nothing from the response at all — not whether a code exists, not whether a
bond is full, not whether it was blocked — and the per-IP bucket bounds the attempts. The
ordinary mistakes still get useful answers. The concurrency guarantees are proved by tests
that fail when either mechanism is removed.

**Negative.** Support will be harder: when a user says "it says my code cannot be used",
neither they nor a support agent can tell which of six things happened, and answering will
need a database query. That is the cost FR-024 buys safety with, and it is worth writing
down rather than rediscovering at a support desk. The ordered checks in `AcceptInvite` are
a sequence somebody could reorder without a test noticing, since the tests assert each
refusal individually rather than their order — a fair criticism, and the KDoc is the
mitigation.

**Neutral.** `resolve` now needs the caller's id, so it takes a `CurrentUser` after all —
detekt had flagged that parameter as unused in the first version, which was correct about
the code as written and wrong about the code as it should have been. Four error codes are
added, so the PR carries `breaking-api-change` under ADR-0024's amendment.

## Alternatives considered

**`410 Gone` for an expired or spent code**, as doc 06 §3.3 specifies. Rejected: "gone"
confirms it was once here, which is exactly what FR-024 forbids. *Becomes right if* invite
codes ever stop being a security boundary — they will not.

**Telling a blocked person nothing different from anyone else, by not checking blocks at
resolve.** Rejected in the middle of the slice; see decision 3. *Becomes right if* never.

**A per-bond advisory lock instead of the row lock.** Equivalent in effect and the
established pattern in this codebase (identity's sessions lock). Rejected because the
contended thing genuinely *is* a row, `FOR UPDATE` needs no key to agree on, and adding a
third advisory namespace for something a row lock expresses directly is a convention
without a reason.

**Checking blocks with two queries, one per direction.** Rejected as one `OR` — the two
questions are one question, and splitting it invites a future edit that fixes one half.

**A `valid` boolean on the resolve response**, per doc 06 §3.3. Rejected: a usable code is
a `200` and anything else is the `404`, so the field would always be `true` — a field that
can only hold one value is a field a client will eventually trust for something else.

## Revisit when

- Support load shows that "that code cannot be used" is genuinely unworkable at a help
  desk, at which point the answer is a support tool that can query the cause, not a more
  specific response.
- A bond type with more than two seats exists, which makes "full" a state a member can
  reach and re-invite out of — the `hasRoom` check in `accept` is written for it already.
- The per-IP bucket is observed refusing real couples sharing an office or a phone network,
  at which point the number changes rather than the design.
