# AGENTS.md

Guidance for coding agents working in this repository.

**The rules, commands and pointers are in [`CLAUDE.md`](CLAUDE.md). Read it first.** It is
written for any agent, not only Claude; this file exists so that tools which look for
`AGENTS.md` find the same guidance without a second full copy to keep true.

Four of its rules are repeated here, in its own words, for tools that read only this
file. `CLAUDE.md` is the source; if the two ever differ, it is right and this file is stale.

1. Every change goes through a pull request; never commit or push to `main`.
2. Do not merge unless the owner says so.
3. Entry text, passwords, tokens and invite codes never reach a log, an exception
   message, or a second column; types that hold them redact their `toString`.
4. Say what was run and what was only read.

Before changing something an ADR decided, read the ADR in `adr/`. Before building a
slice, read its spec in `docs/superpowers/specs/`.
