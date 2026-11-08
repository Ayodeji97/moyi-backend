# Turning on real pushes (FCM)

Every environment uses the `log` push provider today. Pushes are requested, written to the log
by category and bond, never by token, and settled in the ledger as `not-sent:log` (ADR-0044).
This runbook turns production over to Firebase Cloud Messaging. It answers issue #133.

**Owner's ruling, 2026-11-08: production gets its own Firebase project.** `moyi-65b68` stays
the development project, so a development build can never push to a real person, and a
development token can never be sent from production.

Steps 1–3 are the owner's: a Google account, a vault and a key file are involved, and none of
them may pass through an agent. Steps 4–6 are one pull request plus one deploy, done in the same
sitting as step 3. If the PR deploys before the vault holds the fields, `kamal secrets fetch`
fails and **every** deploy stops. If it deploys with the fields empty, the API refuses to start.

## 1. The production Firebase project

1. In the Firebase console, create a project, for example `moyi-prod`. Leave Analytics off: the
   app does not use it, and it is one more processor to disclose.
2. Register the same two apps as in `moyi-65b68`:
   - Android `com.moyi.app`
   - iOS `app.getmoyi.moyi`
3. Project settings → Cloud Messaging → Apple app configuration. Upload the **same APNs auth
   key** (`.p8`) used for `moyi-65b68`. One key serves every project of the Apple team. Fill
   both the development and the production slot, as before.
4. Download `google-services.json` and `GoogleService-Info.plist` for the **client's release
   build**. That is moyi-app's work: its release flavour must use these files, and its debug
   flavour keeps `moyi-65b68`'s. A release build carrying the development files registers
   tokens that production cannot send to (`SENDER_ID_MISMATCH`), and the sender forgets them.

## 2. A service account that can only send

1. Google Cloud console, with the project `moyi-prod` selected → IAM & Admin → Service
   Accounts → Create.
   - Name: `moyi-fcm-sender`
   - Role: **Firebase Cloud Messaging API Admin** (`roles/firebasecloudmessaging.admin`), and
     nothing else. In particular, not Editor or Firebase Admin: this key lives on a server
     and should be able to send a push and nothing more.
2. APIs & Services: check that the **Firebase Cloud Messaging API** (the HTTP v1 API, not
   the legacy one) is enabled.
3. Service account → Keys → Add key → JSON. The file downloads once.

## 3. Into the vault, and the key file gone

In Bitwarden, open the secure note `moyi-prod` and add three hidden fields, copied from the JSON:

| Field | From the JSON | Note |
|---|---|---|
| `FCM_CLIENT_EMAIL` | `client_email` | |
| `FCM_PRIVATE_KEY_PEM` | `private_key` | Paste it as it stands, with its `\n`. `FcmPushSender` reads both real and escaped line breaks. |
| `FCM_PRIVATE_KEY_ID` | `private_key_id` | Optional; it names the key in Google's audit log. |

The project id (`project_id`) is not a secret. It goes in `config/deploy.yml` in step 4.

Then delete the JSON file and empty the bin. The vault is the only copy. If the key is lost,
make a new one and delete the old one in the console.

## 4. The pull request (an agent can do this, once steps 1–3 are done)

`.kamal/secrets`: add the fields to the `kamal secrets fetch` list, and extract them:

```sh
# FCM (ADR-0044, ops/notification/README.md): the service account
# moyi-fcm-sender of the Firebase project moyi-prod, allowed to send and nothing else.
MOYI_NOTIFICATION_PUSH_FCM_CLIENT_EMAIL=$(kamal secrets extract FCM_CLIENT_EMAIL $SECRETS)
MOYI_NOTIFICATION_PUSH_FCM_PRIVATE_KEY_PEM=$(kamal secrets extract FCM_PRIVATE_KEY_PEM $SECRETS)
MOYI_NOTIFICATION_PUSH_FCM_PRIVATE_KEY_ID=$(kamal secrets extract FCM_PRIVATE_KEY_ID $SECRETS)
```

`config/deploy.yml`, **in the `web` role only**, not in the shared `env`:

```yaml
servers:
  web:
    env:
      clear:
        MOYI_NOTIFICATION_PUSH_PROVIDER: fcm
        MOYI_NOTIFICATION_PUSH_FCM_PROJECT_ID: moyi-prod   # the project's real id
      secret:
        - MOYI_NOTIFICATION_PUSH_FCM_CLIENT_EMAIL
        - MOYI_NOTIFICATION_PUSH_FCM_PRIVATE_KEY_PEM
        - MOYI_NOTIFICATION_PUSH_FCM_PRIVATE_KEY_ID
```

**Leave the shared `MOYI_NOTIFICATION_PUSH_PROVIDER: log` where it is.** The media worker
builds a push sender too (`PushSenderConfiguration` is in every profile). It is given no
secrets, so on FCM it would refuse to start. It never sends a push, so `log` is right for it.
Kamal merges a role's `env` over the top-level one, so the `web` role's `fcm` wins for the API
alone.

## 5. Deploy, then read the first lines

Deploy as usual (`kamal deploy --skip-push --version=$(git rev-parse HEAD)`, the full SHA).
Then:

```sh
kamal app logs --roles web --lines 300 | grep -m1 "Push provider"
# expect: Push provider: FCM (project: moyi-prod)
kamal app logs --roles media-worker --lines 300 | grep -m1 "Push provider"
# expect: Push provider: LOG (project: <unset>)
```

If the API logs `moyi.notification.push.provider is FCM ... are not all set`, a field is
missing from the vault or from the `secret:` list. The API refuses to start rather than run
without a way to send, and the old container keeps serving.

## 6. One real push

With a release build of the app on a phone, signed in with a production test account:

1. Make sure the app has registered its token (`PUT /me/devices/current/push-token`; the app
   does this once it holds one).
2. Cause a push. Pairing a second test account sends `PARTNER_JOINED`, which is the quickest.
3. In the API log, expect `FCM accepted a push, category PARTNER_JOINED` at DEBUG, or no WARN
   from `FcmPushSender`. The phone should show the notification in the `partner_joined` channel
   (ADR-0044, decision 9).

| You see | It means |
|---|---|
| `Push token refused ... SENDER_ID_MISMATCH` | The app was built with `moyi-65b68`'s config files. See step 1.4. |
| `Google's token endpoint refused the service account (HTTP 400/401)` | The key is wrong or deleted, or the PEM lost its line breaks. |
| `FCM refused the message (HTTP 403, PERMISSION_DENIED)` | The role is missing, or the FCM API is not enabled. See step 2. |
| `APNs credentials FCM cannot use` / `THIRD_PARTY_AUTH_ERROR` | The APNs key is not uploaded to `moyi-prod`. See step 1.3. |

Wipe the test accounts afterwards, as with the other production checks.
