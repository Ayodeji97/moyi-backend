# Revision Notes — How This Works

Short concept cards from the PL Coding course, checked against 2026 reality, with our own decisions attached. Built to be re-read the week before an interview, not to be comprehensive.

---

## The card format

Every concept gets the same six lines. Nothing longer.

| Line | What goes in it |
|---|---|
| **What** | One or two sentences. If it takes more, the concept needs splitting. |
| **Why it exists** | The problem it solves. This is the part interviewers actually probe. |
| **In Spring Boot** | The concrete dependency, annotation or class. What you'd type. |
| **Moyi** | Do we use it? Same as the course or different? One line of *why*. |
| **2026 check** | ⚠️ if the course material has aged, ✅ if it still holds. |
| **Interview** | Two or three likely questions with short answers. |

## Why "Moyi" and "2026 check" are on every card

Two things separate someone who watched a course from someone who understands the material:

1. **They can say why they chose *not* to use something.** "We didn't use WebSockets, and here's the reasoning" is a stronger answer than having used them. Every divergence from the course is deliberate and is recorded on the card.
2. **They know what changed recently.** Courses date. Knowing that Spring Boot 4 shipped, that virtual threads reshaped the reactive debate, and that Redis changed licence tells an interviewer you follow the ecosystem rather than a playlist.

## The reading protocol

- **After watching a module:** skim the cards, and only then rewatch anything that didn't stick.
- **Before an interview:** read the **Interview** lines only. Fifteen minutes for the whole set.
- **When stuck in the build:** the **In Spring Boot** line is the lookup.
- **Every card you can't answer from memory** goes on a short list, and the next session starts there.

## Index

| Module | Card set | Status |
|---|---|---|
| 1 — Prerequisites | *(no notes needed — setup only)* | — |
| **2 — System Design** | `m02-system-design.md` | ✅ Complete |
| 3 — Multi-Module Setup | `m03-multi-module.md` | Pending |
| … | | |

## The honest caveat

These cards are a *map*, not the territory. Being able to recite "the outbox pattern prevents dual writes" is not the same as having debugged a stuck poller at 1 a.m. The cards are there so that the thing you built has a name you can reach for under pressure — the understanding comes from Phase 1 onward, not from here.
