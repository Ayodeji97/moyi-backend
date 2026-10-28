# Plan — an entry's 500 counted as typed; an entry of only zero-width characters refused (ADR-0031 rulings 1 and 2)

Branch `feat/entry-text-rulings`, cut from `origin/main` 6ecece5. The sixth and last of the
#67 rulings. **No migration.** No contract *schema* change (descriptions only), so no
`breaking-api-change` label — but more requests are refused than before, and the PR body
says which.

**The rulings, 2026-10-21, both "to be built"** (ADR-0031, "Rulings — the owner, 2026-10-21"):

1. The 500 are counted **as typed: grapheme clusters of the text as sent**, not of its NFKC
   form. The test `the 500 is counted on the NFKC form, so an ellipsis counts as three` is the
   one to turn. The blank check and the 8192 octets are not part of the ruling.
2. An entry made only of zero-width characters is **refused as empty, a `422`**.

## Decisions (the controller's; record them in an ADR-0031 amendment under the rulings)

1. **"As sent" means the raw string, untrimmed.** `Graphemes.count(raw)`. Leading and trailing
   whitespace a client sent is stored (decision 23) and is now counted; that is what a
   client's own counter of the text field sees. No NFKC, no trim, in the count.
2. **"Zero-width" is Unicode's `Default_Ignorable_Code_Point` set** (DerivedCoreProperties.txt),
   not a hand-picked list of ZWSP/ZWJ: it is the set Unicode defines as "rendered as nothing".
   The blank check becomes: after NFKC and `trim` (unchanged — the ruling leaves the blank
   check's form alone), the text must hold at least one code point that is neither whitespace
   (Kotlin's `isWhitespace`, as `isNotBlank` uses today) nor default-ignorable. Same message
   as blank today ("an entry must say something"), the same `422`.
   - Put the set in **one place** in `domain` (e.g. `Invisible.isDefaultIgnorable(codePoint)`
     or a function next to `Graphemes`), stated as the ranges of the Unicode version Java 25
     ships, with a KDoc naming the source file and version. Do not use `Character.getType ==
     FORMAT` alone: it misses variation selectors, U+034F, the Hangul fillers (U+115F, U+1160,
     U+3164, U+FFA0) and the tag block, and includes things that are not default-ignorable.
   - **Search is not changed.** `SearchTerms` keeps its own counting and blank rule; whether
     search follows is ADR-0039 question 11, open. Update `Graphemes`' KDoc, which says both
     count the NFKC form today.
3. **Reading a row back must never fail on a row the old rule accepted.** `GratitudeMappers`
   runs `EntryText.of` on every read (its KDoc says so), and both rulings *tighten* the rule:
   a stored 500-grapheme entry with a trailing space is now 501, and a stored zero-width-only
   entry is now refused. Either would make every read of that day a `500`. Development and
   production databases hold test entries written under the old rule, so this is reachable.
   - Split the factory: **`of(raw)`** is the write-path check (all rules, new ones included);
     a read-path factory (name it in the code's style, e.g. `fromStorage(raw)`) checks only
     what storage itself guarantees and a reader relies on — NUL, unpaired surrogate, octets —
     and not blank or the grapheme count. Use it in `GratitudeMappers` only. Update its KDoc
     and `EntryText`'s ("Reading a row back goes through [of] too" is no longer true).
   - Edits (`PatchEntry` / `ChangeEntry`) go through `of` and so meet the new rules: an edit
     of an old over-long entry must fit the new count. That is correct; say it in the ADR.
   - Test: a row inserted by SQL that the new `of` refuses (zero-width only; 500 graphemes +
     a trailing space) is read back through the real repository / an archive route without
     error and with its text unchanged. Mutation: map with `of` again, watch it go red.
4. **Order of checks** stays: NUL, surrogate, octets, blank (now with ignorables), graphemes.
5. **The edge is unchanged** in shape: `ValidEntryText` still asks `EntryText.of`. Update the
   KDocs in `EntryConstraints.kt` and `SubmitEntryRequest.kt` that describe the counted form.
   Do not touch `service/SubmitEntry.kt` beyond what is required — another open PR (#99)
   edits one line of it; if you must change it, keep the change away from that line and say so.

## Tasks

1. **Tests first** (`EntryTextTest` and where the endpoint tests live):
   - Turn `the 500 is counted on the NFKC form, so an ellipsis counts as three` into its
     opposite: 499 letters + `…` (500 as typed, 502 under NFKC) is accepted; 501 as typed is
     refused. Add: 500 letters + one trailing space is refused (counted as sent); a text whose
     NFKC form has *more* graphemes than the raw is decided on the raw.
   - Zero-width only is refused, one case per kind: U+200B, U+200C, U+200D, U+2060, U+FEFF,
     U+00AD, U+FE0F, U+034F, U+3164, U+E0041 (a tag, a surrogate pair), and mixtures with
     whitespace. A zero-width character *with* a visible one (`a​`, a ZWJ emoji
     sequence, a flag) is accepted and stored exactly.
   - Endpoint: `POST` and the edit route answer `422` for a zero-width-only text and `201`/
     `200` for the 499+`…` case; nothing is stored on a `422`.
   - The read-path tests of decision 3.
2. **Implement** in `EntryText.kt` (+ the one-place ignorable set), `GratitudeMappers.kt`;
   KDocs in `Graphemes.kt`, `EntryConstraints.kt`, `SubmitEntryRequest.kt`.
3. **Contract, docs, tools:**
   - The entry text's `description` (contract lines ~4473 and ~4747, generated from the
     request types' schema annotations) says what is counted: "as sent", and that a text of
     only invisible characters is refused. Regenerate `contracts/openapi.json`; read the diff —
     descriptions only. Do **not** change search's description.
   - `docs/state-map/data/endpoints.json` rows for `POST …/entries` and the edit route: add
     "or only invisible characters" to the `422` reason. Run `scripts/state-map-check --strict`
     (it catches stale code quotes — fix any it names).
   - `scripts/smoke.sh`: probes that a zero-width-only entry is `422` and that 499 letters +
     `…` is `201` (use a bond/day the script can spare; follow its existing helpers). Read the
     comment at ~line 953 (NFKC) and correct anything it now states wrongly.
   - ADR-0031: an amendment under "Rulings — the owner, 2026-10-21" saying both are built,
     with decisions 1–5; strike or annotate decision 23's "grapheme count on the NFKC form"
     bullet and the class-KDoc paragraph about the client counter (the cost is gone; the new
     cost is that the server counts trailing spaces). Bump **Amended:**.
   - Note as owed, not done: the corpus (spec §3.2, doc 04 §7 say "NFKC-normalised before
     counting") is another repository; the KMP client's counter (moyi-app) should count
     graphemes of the raw text.
4. **Mutations** (each: break, watch red, restore): count on the NFKC form again; drop the
   ignorable check; map reads through `of`.

## Verify before "ready"

1. `./gradlew build --rerun-tasks` with JDK 25 (`$HOME/.sdkman/candidates/java/25.0.4-tem`);
   count tests from the JUnit XML.
2. Smoke on your own compose project and database — never touch a container you did not start:
   ```
   S=<scratchpad>; cat > $S/compose-0f.yml   # services.postgres.ports: !override ["55443:5432"]; services.redis.ports: !override ["56390:6379"]
   export COMPOSE_PROJECT_NAME=moyi-0f COMPOSE_FILE=docker-compose.yml:$S/compose-0f.yml
   docker compose up -d postgres redis; docker compose exec -T postgres createdb -U moyi moyi_0f
   java -jar app/build/libs/app-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.port=18180 \
     --management.server.port=18181 --spring.datasource.url=jdbc:postgresql://localhost:55443/moyi_0f \
     --spring.data.redis.port=56390 '--moyi.scheduling.close.cron=*/5 * * * * *' > $S/app.log 2>&1 &
   BASE=http://localhost:18180 MGMT_BASE=http://localhost:18181 MOYI_DB=moyi_0f MOYI_LOG=$S/app.log scripts/smoke.sh --attach
   ```
   Check the ports are free first. Kill the jar and `docker compose down -v` (that project only)
   afterwards. Update the smoke header's "Last run" paragraph with the measured result.
3. `scripts/state-map-check --strict`.
4. A learning-log entry only if something surprised you.
5. Commits: Conventional Commits, subject ≤ 88 characters, `git config core.hooksPath .githooks`.
   Each commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push.

Report measured numbers only, and say what was run and what was only read.
