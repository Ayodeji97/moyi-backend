# Plan: a display name holds no control or format characters

Branch `feat/display-name-controls`, cut from `origin/main` 7656192.

- **What it is:** a follow-up owed since #97, which put the partner's name on every archive
  day (ADR-0036 q5).
- **No migration and no new ADR number:** it amends ADR-0015 (registration). ADR-0047 is the
  latest, and gratitude-3f may take the next ones.
- **No contract schema change:** the `displayName` description changes, and nothing else. So
  the PR has no `breaking-api-change` label. More registrations are refused, and the PR body
  says which.

## Why

A display name is shown to the other member, in the bond, on every archive day, and in
emails (`SecurityNotice.displayName`). Today identity refuses only blank names and names
over 80 UTF-16 units.

- **Controls:** a name may be made of C0/C1 control characters. JSON writes each as six
  octets, which is what spent the archive page's margin (learning log 2026-10-27).
- **Bidi controls:** a name may carry U+202E and its kin, which reorder the text around them
  on the partner's screen and in an email. That is a spoofing tool.

## Decisions (the controller's; record them as an amendment to ADR-0015)

1. **What is refused:**
   - **Every `Cc` (control) code point**, tab and newline included. A name is one line.
   - **Every `Cf` (format) code point, except U+200C ZWNJ and U+200D ZWJ.** `Cf` holds the
     bidi controls (U+200E/F, U+202A–202E, U+2066–2069), the zero-width space, word joiner
     and BOM, the soft hyphen, and the tag characters.
   - **Why ZWNJ and ZWJ are kept:** they are how Persian, Urdu and several Indic scripts are
     correctly written (ZWNJ), and how emoji sequences are built (ZWJ). Refusing them would
     refuse real names.
   - Use `Character.getType(codePoint)`, per code point. A surrogate pair is one code point,
     and the tag block is outside the BMP.
   - The message names the rule, never the name: "display name must not contain control or
     formatting characters".
2. **The write path only.** `User.init` runs when every row is read
   (`IdentityMappers.toDomain`), so a rule there would make an existing account with such a
   name unreadable. Every sign-in, every `/me` and every bond view of it would become a `500`.
   - Put the rule in **one place** in `identity.domain`. Shape it in the module's style:
     either a `DisplayName` check function, or a small `object DisplayNames { fun check(raw) }`.
   - Call it where a name is **written**:
     - `RegisterUser`, and any other path that sets a name: search for every writer of
       `displayName` / `display_name`, `UserEntity.displayName` setters included.
     - Today there is no rename route. If one exists, it goes through the check too.
   - `User.init` keeps exactly the rules it has, blank and at most 80, which mirror V2's
     CHECK constraint.
3. **The edge asks the domain.** Add a Bean Validation constraint on
   `RegisterRequest.displayName` that runs the domain check, the way `ValidEmail` does
   (read it and copy its shape).
   - Keep `@NotBlank` and `@Size(max = User.MAX_DISPLAY_NAME_LENGTH)`: `@Size` is what
     publishes `maxLength`, which `OpenApiContractTest` holds to `ArchivePageBudget`.
   - The check runs on the **trimmed** value, because the trimmed value is what is stored
     (`RegisterRequest.toCommand` trims). Check what `trim()` removes: Kotlin's `trim()`
     removes whitespace, and some `Cc` characters (tab, newline) are whitespace. So a name
     with a trailing newline is trimmed and accepted, and a newline inside the name is
     refused. Pin both by test.
   - The answer is a `422`, in the same problem shape as the other field errors.
4. **Existing rows are untouched.** No migration rewrites them. Production holds only test
   accounts. Say this in the ADR.
5. **The archive page budget.** The gratitude largest-page test builds its worst name from
   `\u001F` through a fake user directory. That name is now worse than identity allows.
   - So the measurement still bounds the page: it over-counts.
   - **Do not change `DaysFeedTest` or `ArchivePageBudget` in this PR.** #105 rewrites that
     test, and the two would conflict.
   - Record in ADR-0015's amendment, and as a line in ADR-0036 decision 6's notes, that the
     worst name identity now accepts escapes to at most 3 octets a unit. That is a BMP
     character's UTF-8, or 2 octets for `"` and `\`, against 6 before. Re-measuring the page
     with it is owed once #105 has merged.

## Tasks

1. **Tests first:**
   - **Identity domain:** one case each for U+0000, U+0009 inside, U+000A inside, U+001F,
     U+007F, U+0085, U+200B, U+200E, U+202E, U+2066, U+FEFF, U+00AD and U+E0041, all refused.
   - **Accepted:** a Persian name with ZWNJ, an emoji ZWJ family, a flag, accented letters
     (precomposed and combining), and a CJK name. Each is stored exactly as sent, apart from
     today's trim.
   - **Endpoint:** `POST /api/v1/auth/register`, or whatever the register route is, answers
     `422` naming `displayName`, writes nothing (no user row, no consent rows), and sends no
     email.
   - **Read path:** a user row whose `display_name` is updated by SQL to contain U+202E still
     reads back. Use a sign-in or `/me` and the `UserDirectory` lookup. Mutation: put the
     rule in `User.init`, and watch this test go red.
2. **Implement:** the domain check, the edge constraint, and the call(s) in the write path.
3. **Contract, docs, tools:**
   - `displayName`'s description says what is refused.
   - Regenerate `contracts/openapi.json` (OpenApiContractTest prints the `cp` command), and
     read the diff: it should be descriptions only.
   - `docs/state-map/data/` row(s) for register: add the reason to the `422`. Run
     `scripts/state-map-generate`, then `scripts/state-map-check --strict`.
   - Smoke: one probe that a display name with U+202E is a `422`. Follow the script's
     helpers (`register_body` takes a name).
   - ADR-0015 amendment with decisions 1–5, and a bump of its **Amended:** line. Add the
     ADR-0036 note from decision 5.
4. **Mutations** (each: break it, watch the tests go red, restore it):
   - drop the `Cf` check;
   - drop the ZWNJ/ZWJ exception;
   - drop the edge constraint;
   - move the rule into `User.init`.

## Verify before "ready"

1. Run `./gradlew build --rerun-tasks` with JDK 25 (`export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.4-tem`),
   and count the tests from the JUnit XML. Only one Gradle run at a time: memory is tight.
2. Smoke: run `/private/tmp/claude-501/-Users-danzucker-Desktop-Learning-project-base-learning-Gratitude/9e9410be-2fa6-4fe5-954d-2693ce0772a3/scratchpad/verify-all.sh feat-display-name-controls`.
   - It builds, then boots the jar with every flag the smoke script needs, against compose
     project moyi-0f (Postgres 55443, Valkey 56390, MinIO 59100) and a fresh database. Then
     it runs `scripts/smoke.sh --attach` and tears moyi-0f down.
   - Its output lines are "BUILD …" and "SMOKE …".
   - Never touch any other container.
   - Then update the smoke header's newest "Last run" sentence with the measured result.
3. Run `scripts/state-map-check --strict`.
4. Commits:
   - Run `git config core.hooksPath .githooks` first.
   - Follow Conventional Commits, subject at most 88 characters.
   - End each body with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
   - Do not push.
   - Write invisible characters in source as `\u` escapes, never as literals. Scan the diff
     before reporting.
   - A display name is not a secret, but no log line may print a raw refused name.

Report measured numbers only, and say what was run and what was only read.
