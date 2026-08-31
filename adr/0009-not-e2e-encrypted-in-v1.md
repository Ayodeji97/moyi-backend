# ADR-0009 — Not end-to-end encrypted in v1

**Status:** Accepted, with a mandatory review before public launch · **Date:** 2026-08-30

## Context
The system stores what two people write about each other — among the more sensitive categories of consumer data, potentially including Art. 9 special-category data incidentally (`09` §6). E2EE would be the ethically strongest position and a genuine differentiator. It would also foreclose server-side search, all planned ML, automated media moderation, and any ability to help a user recover data.

## Decision
Not end-to-end encrypted in v1. Instead: full-disk encryption; client-side-encrypted backups; **application-level encryption of `entries.text` (AES-256-GCM, data key wrapped by a master key in a self-hosted Vault container with its own storage and manual unseal, so the key does not sit in plaintext beside the database), shipped in Phase 5 before public launch**; strict access control with audited, justified, user-disclosed admin access; and a Privacy Policy that states plainly, in ordinary language, that entries are not end-to-end encrypted.

## Consequences
**Positive:** search, ML, moderation and support all remain possible. Account recovery works normally. Key management stays tractable for a solo developer. Application-level encryption still defeats the most likely realistic attack — database exfiltration without the key. Search continues to work against a lossy, irreversible token projection (`07` §2), which is a partial disclosure and is disclosed as such.
**Negative:** a server compromise *with* key access exposes content, and a manual unseal means a host reboot needs a human before the service is fully functional. The `text_search` projection is a deliberate, documented partial disclosure. The strongest possible privacy claim is unavailable. Some privacy-conscious users will reasonably decline to use it. This is the weakest point in the design, and saying so is the point of writing it down.
**Neutral:** honesty in the Privacy Policy is itself a differentiator — most apps in this space are considerably vaguer.

## Alternatives considered
- **Full E2EE with per-Space keys** — the right long-term answer. Rejected for v1: two-device key exchange, key backup, account recovery without a password reset destroying all data, and multi-device sync would collectively consume the entire project budget. Doing E2EE badly is worse than not claiming it.
- **Plaintext with no additional encryption** — rejected: not defensible for this content.

## Revisit when
**Mandatory review before public launch** (`20` §2). Also revisit if the user base grows beyond the founder's network, if a user explicitly requests it, or if a breach occurs anywhere in the stack.
