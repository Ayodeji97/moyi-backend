## What & why

<!-- One or two sentences. The diff shows what; explain why. -->

## Self-review (doc 18 §6)

Read the diff as a hostile reviewer and answer honestly — this replaces
having a teammate review the PR:

- [ ] **Worst input?** Null, empty, 10 MB, malformed UTF-8, a negative
      number, a date in 1970, a timezone that no longer exists.
- [ ] **Runs twice concurrently?** What happens?
- [ ] **Process dies halfway through?** What state is left behind?
- [ ] **Can another user reach this data?**
- [ ] **What does this log** — would any of it embarrass a user?
- [ ] **Debuggable at 2 a.m. from a trace id alone?**
- [ ] **Does this query use an index?** Have I checked?
- [ ] **Simplest thing that works, or the cleverest?**
- [ ] **Will I understand this in six months?**
- [ ] **Did I write the test that would have caught the bug I just fixed?**

## Definition of Done (doc 18 §5)

- [ ] Compiles; ktlint and detekt clean
- [ ] Unit tests for the logic; integration tests for the boundary
- [ ] Coverage gates met
- [ ] Architecture tests pass
- [ ] Error cases handled and tested, not just the happy path
- [ ] Input validated; failure response doesn't echo input
- [ ] Authorisation checked; cross-tenant test covers any new endpoint
- [ ] No secret/token/password/entry content can reach a log
- [ ] OpenAPI spec regenerated if the API changed
- [ ] Docs updated in this PR if `06`/`07`/other docs are now wrong
- [ ] ADR written if an architectural choice was made
- [ ] A line added to `docs/learning-log.md`
