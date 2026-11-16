# site — the pages the emails link to

`getmoyi.app`, served as static files by Cloudflare Pages from this folder
(build command: none; output directory: `site`). Three addresses carry something.
On a phone with the Moyi app, each opens the app (see "App links"); the page
here is what shows when it does not: the app is not installed, the link was
opened on a computer, or an in-app browser (Gmail's, for one) kept it.

| Address | Made by | Opens in the app | The page shows |
|---|---|---|---|
| `/verify?token=…` | the confirmation email | Verify, checking the token at once | "Open in the Moyi app", and the link to copy |
| `/reset-password?token=…` | the reset email | Choose a new password, on the password step | "Open in the Moyi app", and the link to copy |
| `/i/<code>` | `moyi bond new` (InviteLinks.kt) | the join screen with the code filled in, once signed in | "Open in the Moyi app", and the code to copy |

Each page has the same three parts, for real people, with no developer tool on
any of them:

1. **Open in the Moyi app**: a link to the same address on `open.getmoyi.app`
   (see "The open host"). A tap to a different host is what makes iOS and
   Android hand a link to the app; a tap to the page's own host stays in the
   browser. Without the app it comes straight back to this page.
2. **The stores**: the app is coming to the App Store and Google Play. There
   is no listing yet, so there are no store links. Add them here when there is.
3. **Copy by hand, as a last resort.** For verify and reset the page copies the
   whole link, not the bare token: the app's code field takes either six digits
   or a pasted link containing `token=` (moyi-app `parseCodeInput`), and a bare
   token pasted there would be read as digits. The 6-digit code in the email is
   a different value from the link's token (`PresentedCredential`), and the page
   cannot show it, because it is not in the link; the page says the emailed code
   works too. For an invite it copies the code, which the join field takes
   either way.

They **fetch nothing**. `_headers` sets `default-src 'none'`, so the browser
refuses any request the pages might make, and opening a link spends nothing:
an email scanner that follows it gets a page, and no request reaches the API
(ADR-0018). The token goes from the address bar to the clipboard, or to the
app by the tap, and nowhere else. `Referrer-Policy: no-referrer` keeps it out
of the next page's Referer, and the token pages are `no-store`.

`link.js` is the one script. It accepts only the shapes the app's own parser
accepts (moyi-app `DeepLink.parse`): a token of letters, digits, `-` and `_`,
at most 128 long; an invite code of letters and digits, at most 16. Anything
else shows "This link looks incomplete", rather than being handed to the app.
It checks no rule of the server's: whether the value works is the server's to
say.

Try it locally with Cloudflare's own server, which applies `_headers` and
`_redirects` the way production does:

```
npx wrangler pages dev site --port 8788
open "http://localhost:8788/verify?token=abc_DEF-123"
```

Locally, "Open in the Moyi app" still points at `https://open.getmoyi.app`, the
real host: the button is the one part that can only be tried on a phone.

## App links

Two files in `.well-known/` let the phone open these links in the app instead
of the browser, once the app is installed. Each operating system fetches its
file from `https://getmoyi.app/.well-known/…` (and from
`https://open.getmoyi.app/.well-known/…`, the same files: see "The open host")
and checks it names the app.

| File | For | Says |
|---|---|---|
| `assetlinks.json` | Android App Links | package `app.getmoyi.moyi`, and the SHA-256 of the certificate the app is signed with |
| `apple-app-site-association` (no extension) | iOS Universal Links | app `7DUJFVWF7W.app.getmoyi.moyi`, and the three addresses below |

The Apple file lists exactly the three addresses the app routes (moyi-app
`DeepLink.parse`, `LinkRouting`), and nothing else, so `/`, `/invite` and the
rest of the site stay in the browser:

```json
"components": [
  { "/": "/verify", "?": { "token": "?*" }, "comment": "The confirmation email: /verify?token=<token>" },
  { "/": "/reset-password", "?": { "token": "?*" }, "comment": "The reset email: /reset-password?token=<token>" },
  { "/": "/i/?*", "comment": "An invite link: /i/<code> (InviteLinks.kt)" }
]
```

The Android manifest claims the same three (`autoVerify` intent filter in
moyi-app `AndroidManifest.xml`: `/verify`, `/reset-password`, `/i/` prefix),
so `assetlinks.json` is checked by every install. Keep the three lists equal:
a path in one and not the other opens the app on one platform only. They
match the backend's link builders: `VerificationEmailComposer.linkFor` (the
base from `MOYI_IDENTITY_VERIFICATION_LINK_BASE_URL` and
`..._RESET_LINK_BASE_URL`, plus `?token=`) and `InviteLinks.linkFor`
(`MOYI_BOND_INVITE_LINK_BASE_URL` plus `/<code>`), `config/deploy.yml`.

Where the values come from:

- `7DUJFVWF7W` is the Apple Developer Team ID (Membership details), and
  `app.getmoyi.moyi` the bundle ID and the Android `applicationId`
  (moyi-app, `app.getmoyi.moyi` on both platforms).
- The fingerprint in `assetlinks.json` is the **upload key's** SHA-256
  (`keytool -list -v -keystore <upload keystore>`). It covers a build signed
  with that key, side-loaded or from Android Studio.

**Owed (owner, Play Console), before any install from Google Play opens a
link:** Play re-signs the app with its own App Signing key, so an install from
Play carries a different certificate and the links would open in the browser. Copy that key's
SHA-256 from Play Console (Test and release → App integrity → App signing) and
add it as a second entry in `sha256_cert_fingerprints`, keeping the upload
key's.

Both files go out as `Content-Type: application/json` (`_headers`), with no
redirect; Apple refuses the file if it is redirected. Cloudflare Pages uploads
dot-directories (Wrangler skips only `.git`, `.DS_Store`, `node_modules` and
its own files), so nothing extra is needed. Check after a deploy:

```
curl -sI https://getmoyi.app/.well-known/assetlinks.json
curl -sI https://getmoyi.app/.well-known/apple-app-site-association
curl -s https://getmoyi.app/.well-known/apple-app-site-association | python3 -m json.tool
```

## The open host

`open.getmoyi.app` exists only so that "Open in the Moyi app" is a tap to a
**different host**. iOS opens a universal link only when the tap leaves the
page's own host, and Android verifies App Links per host, so a button on
`getmoyi.app` pointing at `getmoyi.app` would stay in the browser. The app
claims both hosts.

- With the app installed, the tap opens the app at the same address.
- Without it, the browser asks `open.getmoyi.app` for the address, and a
  Cloudflare Redirect Rule sends it (302) to the same path and query on
  `getmoyi.app`: this page again. Nothing redirects on `getmoyi.app`, so there
  is no loop.
- `/.well-known/*` is the one thing never redirected there: Apple and Android
  fetch both files from each host they verify, and Apple refuses a redirected
  file.
- Should the rule ever be missing, the same project serves the page on the
  open host, with a button pointing at itself. `link.js` covers that: on
  `open.getmoyi.app` it moves the page to `getmoyi.app` at once
  (`location.replace`, no request of its own).

The token is in the button's address, so it travels to Cloudflare as the
original link did, and no further: `Referrer-Policy: no-referrer` holds on
both hosts, and a redirect carries no Referer of its own.

**Owner steps, in the Cloudflare dashboard** (zone `getmoyi.app`):

1. **Add the host to the Pages project.** Workers & Pages → `moyi-site` →
   Custom domains → Set up a custom domain → `open.getmoyi.app`. Cloudflare
   adds the DNS record (`CNAME open → moyi-site.pages.dev`, proxied) itself,
   since the zone is on Cloudflare; wait until the domain shows **Active**.
2. **Add the redirect.** Rules → Redirect Rules → Create rule (a Single
   Redirect), named `open host back to the site`:
   - When incoming requests match, custom filter expression (Edit expression):
     ```
     (http.host eq "open.getmoyi.app" and not starts_with(http.request.uri.path, "/.well-known/"))
     ```
   - Then: URL redirect, type **Dynamic**, expression
     `concat("https://getmoyi.app", http.request.uri.path)`, status code
     **302**, **Preserve query string** ticked.
   - Deploy.
3. **Check it** (any token will do; the pages check nothing):
   ```
   curl -sI https://open.getmoyi.app/.well-known/apple-app-site-association   # 200, application/json, no Location
   curl -sI https://open.getmoyi.app/.well-known/assetlinks.json              # 200, application/json, no Location
   curl -sI "https://open.getmoyi.app/reset-password?token=abc"               # 302, Location: https://getmoyi.app/reset-password?token=abc
   curl -sI https://open.getmoyi.app/i/7KQ4MZ                                 # 302, Location: https://getmoyi.app/i/7KQ4MZ
   curl -sI "https://getmoyi.app/reset-password?token=abc"                    # 200, no Location
   ```

**Owed by the app** (moyi-app), before the button opens anything: the iOS
entitlement `applinks:open.getmoyi.app` beside `applinks:getmoyi.app`; an
`autoVerify` intent filter for `open.getmoyi.app` with the same three paths;
and `DeepLink.parse` accepting both hosts (today it takes `getmoyi.app` only,
`DeepLink.HOST`). Until then the button lands back on this page, and the
emailed link itself opens the app as before.
