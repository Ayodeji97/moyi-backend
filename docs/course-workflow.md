# How a course topic becomes shipped code

This project is built alongside Philipp Lackner's *Building
Industry-Level Kotlin Backends With Spring Boot*, adapting it from the
course's chat domain to this one. This file records how a topic goes from
"watched the lesson" to "merged, tested and written up", and who does
which part.

The loop itself is not invented here — it is the one defined in the
planning corpus (`16-learning-plan-and-course-mapping.md` §1). What this
file adds is the division of labour and the shape of the briefing.

---

## The loop

| # | Step | Who | What happens |
|---|---|---|---|
| 1 | **WATCH** | Daniel | Watch the lesson end to end, no coding. Notes on the *why* only. |
| 2 | **PREDICT** | Claude → Daniel | Claude posts a **briefing** (format below) — how this applies here and what will differ from the chat app. **This is a hard stop.** No code until Daniel says go. |
| 3 | **BUILD** | Claude | Implement it, in PR-sized slices. |
| 4 | **COMPARE** | both | Where did the course do it differently? Better, or just different because the domain differs? Divergences get an ADR. |
| 5 | **RECORD** | Claude | An entry in [`learning-log.md`](./learning-log.md) — what was *surprising*, not what was built. |
| 6 | **TEST** | Claude | The tests that prove the edge cases were understood, not just the happy path. |
| 7 | **TEACH** | Daniel | For the big topics, the public write-up (`01` L-9). |

Step 2 is the one that is always skipped and the one that does the work.
It is a stop, not a notification.

## The briefing

When Daniel names a topic ("we're on authentication"), Claude answers with:

1. **Where it sits** — which course module, which of our phases and
   milestones, and what already exists in the repo that this builds on.
2. **Course vs. us** — what the course teaches here, what our documents
   already decided, and the ADR behind each deliberate divergence. Doc 25
   §3 lists seven of these; they are not up for renegotiation without a
   new ADR.
3. **Capabilities** — the numbered functional requirements this delivers
   (doc 03), so "done" is defined before starting.
4. **Endpoints** — the API surface (doc 06).
5. **Data** — tables and columns touched (doc 07), and whether the
   migration is backward-compatible with the deployed release.
6. **Risks** — relevant threats (doc 09) and any open questions (doc 21)
   that this topic forces closed.
7. **Proposed slices** — how it breaks into PRs.
8. **Decisions needed** — anything Claude should not decide alone.

Then Daniel corrects or approves, and only then does code get written.

## Done, per topic

A topic is finished when all of these are true — the same standard as
doc 18 §5, plus the learning artefacts:

- [ ] Code and tests, with error cases covered, not just the happy path
- [ ] `./gradlew build` green: lint, detekt, architecture rules, coverage
- [ ] An entry in `docs/learning-log.md`
- [ ] A card in `revision/mNN-*.md` and its row in the index updated
- [ ] An ADR if an architectural choice was made
- [ ] Documentation updated in the *same* PR if this made it wrong
- [ ] A PR, opened with the self-review checklist filled in — **never
      self-merged**

## An honest note about who types the code

Doc 16 §1 is unambiguous: *"never copy-paste from the course into this
repository. Typing it yourself is slower and is the entire point."* It
also names the failure mode it is defending against — "watching a lesson,
following along, feeling competent, and being unable to reproduce any of
it a month later."

**We have deliberately traded away part of that.** Claude writes the
code; Daniel's understanding comes from the briefing, from reviewing the
PR, and from the notes. This is a real trade with a real cost, recorded
here rather than left implicit, because a project that documents its
decisions should document the uncomfortable ones too.

What compensates:

- The briefing is substantive and comes *before* any code, so the design
  reasoning is done jointly rather than discovered afterwards.
- Every PR carries the ten hostile-reviewer questions from doc 18 §6,
  answered rather than ticked. Reading a diff critically is real work.
- The learning log records what was *wrong or surprising*, which is only
  possible if the material was actually understood.

What this makes more load-bearing, not less:

- **Doc 16 §4's competency checklist** — "tick only when you can do it
  from scratch, without notes, and explain the trade-offs". Reviewing
  code does not tick a box there. Writing it from memory does.
- **The M3/M5/M9 assessment gates** (doc 16 §7), including explaining
  the system aloud for 45 minutes. That is the real test of whether this
  trade worked.

If the gates start failing, the trade was wrong and this section should
change.
