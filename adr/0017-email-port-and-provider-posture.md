# ADR-0017 — The email port, and how a provider is allowed to be absent

**Status:** Accepted · **Date:** 2026-09-23

## Context

Email verification (FR-002, slice D) is the first thing in the system that has to leave it — the first outbound call to a third party. Doc 25 D7 already decided the shape in one paragraph: *every external provider sits behind our own interface, defined in the owning module's `api` package; the vendor appears only in one `internal` implementation class.* Building the first one surfaced four questions that paragraph does not answer, and each has a wrong answer that looks fine.

**Who implements the port.** The layer table in `ArchitectureTest` said `service` implements `api` and `infra` may import only `domain`. An email sender has no domain rules to orchestrate — it is a port and a transport — so following the table literally means a `service` class whose entire body is one delegating call, or a second interface in `domain` mirroring the one in `api`. Both exist to satisfy the table, not the design.

**What happens when the provider is not configured.** The intuitive default is a harmless logger with Resend as the opt-in. That default means a production environment missing one variable boots cleanly, reports healthy, and sends nothing — with no symptom except users who never receive a verification email. ADR-0012 and ADR-0016 already refused this fail-open shape for the breach corpus.

**Vendor SDK or HTTP.** Resend publishes a Java SDK. Its API for this slice is one `POST /emails` with five fields.

**How a send reports failure.** Chirp's `EmailService` catches `MailException`, logs at ERROR and returns nothing — the caller cannot tell a sent email from a lost one. Doc 18 §3 says failures are a sealed result at module boundaries, and this is one; NFR-026 says a provider outage must not fail a user-facing write, which the caller can only honour if it can see the outage.

## Decision

**1. `notification.api.EmailSender` is the port, and `notification` owns it.** Doc 05 §2 gives email dispatch to `notification`; `identity` will depend on the module for the `api` package only. The message type `EmailMessage` carries recipient, subject, plain text, optional HTML and an optional idempotency key. The sender address is provider configuration, not a message field — it is a property of this system, and letting a caller choose it is how a caller sends from an unverified domain.

**2. An `infra` adapter may implement its own module's `api` when the contract is pure transport.** `ArchitectureTest`'s layer rule now allows `infra → api`, with the KDoc naming the condition: nothing to orchestrate. An `api` package holds interfaces and DTOs that depend on nothing, so importing one is never a dependency on behaviour, which is the same reason `service` was already allowed to. A contract with domain rules behind it is still implemented in `service`. The rule was re-proven against a planted `infra → service` import before this was written down.

**3. The default provider is the real one, and the real one refuses to start without its key.** `moyi.notification.email.provider` defaults to `RESEND`; `ResendEmailSender.create` fails the context if `resend.api-key` or `from` is missing, with a message naming both the property and the alternative. `LOG` must be asked for by name and only the `local` profile does. A `@Bean` with an exhaustive `when` selects the implementation, so adding a provider and forgetting to wire it is a compile error rather than a missing bean.

**4. `RestClient` against Resend's HTTP API, not the SDK.** One class knows the URL, the header and the five fields. Timeouts (2 s connect, 5 s read) are configured on the builder by the composition root, not by the sender — the sender's tests bind a mock server to the builder they pass in, and a sender that installed its own request factory replaced the mock and tested the real network. It did, on the first run. The idempotency key is forwarded as Resend's `Idempotency-Key`, so a retry after an ambiguous failure cannot deliver twice.

**5. `send` returns a sealed `EmailDelivery` and never throws.** `Accepted(providerMessageId)`, `Rejected(reason)` for a 4xx — the same message will fail again — and `Unavailable(reason)` for a 5xx, a timeout or a 429, which is a 4xx by number and transient by nature. Every call site has to write down what a failed send means for its operation. The adapter logs status codes only: Resend's validation messages quote the field that failed, and for a bad `to` that is the address (NFR-044).

## Consequences

**Positive.** A mute production is a failed deploy, visible at the first log line. Swapping Resend for Postmark is one class and one `when` branch. `identity`'s tests fake a five-line interface instead of a vendor client. The retry decision the outbox (ADR-0008) will need — permanent or transient — is already made at the edge that has the information.

**Negative.** Two configuration values are now required to boot anything but `local`, and every test context that boots the composition root has to set the provider to `LOG` — `app`'s test profile does; each module that later depends on `notification` will have to as well. The layer rule is one edge looser than it was, and the condition guarding that edge is prose in a KDoc, not a check.

**Neutral.** There is no `enabled` flag and no "disabled" provider. An environment that must not send email says `LOG` and gets the messages in its logs.

## Alternatives considered

- **A `service` layer implementation delegating to an `infra` transport**, keeping the layer rule as it was. Rejected: a class with no behaviour, existing to satisfy a table. Becomes right the day the port acquires rules — dedupe, quiet hours, a `notification_log` — at which point the adapter moves behind a service and the rule need not change again.
- **Logger as default, Resend as opt-in.** Rejected above: fail-open by configuration, invisible from every dashboard. There is no future condition under which this becomes right.
- **`@ConditionalOnProperty` on two `@Bean` methods.** Works, and is idiomatic Boot. Rejected for the exhaustiveness argument: a third provider added to the enum and not wired is a startup failure with the conditional approach and a compile error with the `when`.
- **The Resend Java SDK.** Rejected: a dependency to audit and a set of vendor types to keep out of the module, for a one-endpoint API. Becomes right if the integration grows to webhooks, batch sends or templates hosted at the provider.
- **Sending asynchronously inside the port.** Rejected: whether a send is deferred, retried or queued is the caller's transaction's concern (ADR-0008), and a port that hides a thread pool hides the failure with it.

## Revisit when

A second consumer of `EmailSender` needs behaviour the port does not have — templates, per-user preferences, a delivery log — which is the signal to put a `service` in front of the adapter. Or when the outbox lands and the `Unavailable` branch gets its retry, which may change what the adapter should classify as transient.
