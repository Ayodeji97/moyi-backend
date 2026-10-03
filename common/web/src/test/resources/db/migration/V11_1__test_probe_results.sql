-- TEST ONLY — common:web's own test classpath, never shipped (src/test/resources
-- is not part of any artifact another module or `app` sees).
--
-- `common:web` owns no domain table, but the idempotency tests need a real
-- domain write inside the same transaction as the key, so they can prove the
-- two commit or roll back together. This is that write's target: the stand-in
-- for `entries` that IdempotentExecutionTest and IdempotencyInterceptorTest's
-- probe controller insert into and re-read on a replay. V11_1 sorts directly
-- after V11 and before any real module's migration could ever share this
-- classpath.
CREATE TABLE probe_results (
    id     uuid PRIMARY KEY,
    -- A test label, re-read on a replay. NULL is the probe's tombstone.
    label  text
);
