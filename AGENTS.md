# AGENTS.md

Guidance for coding agents working in this repository.

**The rules, commands and pointers are in [`CLAUDE.md`](CLAUDE.md). Read it first.** It is
written for any agent, not only Claude; this file exists so that tools which look for
`AGENTS.md` find the same guidance, and so that there is one file to keep true rather
than two.

The four that matter most, so they are not missed:

1. Every change goes through a pull request. Never commit or push to `main`, and do not
   merge unless the owner says so.
2. Read the ADR in `adr/` before changing something it decided, and the spec in
   `docs/superpowers/specs/` before building a slice. Green tests against the wrong
   requirement are still wrong.
3. `./gradlew build` must pass, and anything that touches an endpoint is also run with
   `scripts/smoke.sh`. Say what was run and what was only read.
4. Entry text, passwords, tokens and invite codes never reach a log.
