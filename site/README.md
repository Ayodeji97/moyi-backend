# site — the pages the emails link to

`getmoyi.app`, served as static files by Cloudflare Pages from this folder
(build command: none; output directory: `site`). Three pages carry something:

| Address | Made by | Shows |
|---|---|---|
| `/verify?token=…` | the confirmation email | the token, and `scripts/moyi verify <token>` |
| `/reset-password?token=…` | the reset email | the token, and `scripts/moyi reset-password <token>` |
| `/i/<code>` | `moyi bond new` (InviteLinks.kt) | the code, and `scripts/moyi bond join <code>` |

They **fetch nothing**. `_headers` sets `default-src 'none'`, so the browser
refuses any request the pages might make, and opening a link spends nothing:
an email scanner that follows it gets a page, and no request reaches the API
(ADR-0018). The token goes from the address bar to the clipboard and nowhere
else. `Referrer-Policy: no-referrer` keeps it out of any page opened next, and
the token pages are `no-store`.

`link.js` is the one script. It offers a value as part of a shell command only
when it is letters, digits, `-` and `_`, so a crafted link cannot make somebody
paste `$(…)` into a terminal; it checks no rule of the server's.

Try it locally with Cloudflare's own server, which applies `_headers` and
`_redirects` the way production does:

```
npx wrangler pages dev site --port 8788
open "http://localhost:8788/verify?token=abc_DEF-123"
```

## App links

Two files in `.well-known/` let the phone open these links in the app instead
of the browser, once the app is installed. Each operating system fetches its
file from `https://getmoyi.app/.well-known/…` and checks it names the app.

| File | For | Says |
|---|---|---|
| `assetlinks.json` | Android App Links | package `app.getmoyi.moyi`, and the SHA-256 of the certificate the app is signed with |
| `apple-app-site-association` (no extension) | iOS Universal Links | app `7DUJFVWF7W.app.getmoyi.moyi`, and the three addresses above: `/verify` and `/reset-password` with a `token` query value, and `/i/<code>` |

Any other address, this page and `/invite` included, stays in the browser: an
address the Apple file does not list is not the app's.

Where the values come from:

- `7DUJFVWF7W` is the Apple Developer Team ID (Membership details), and
  `app.getmoyi.moyi` the bundle ID and the Android `applicationId`
  (moyi-app, `app.getmoyi.moyi` on both platforms).
- The fingerprint in `assetlinks.json` is the **upload key's** SHA-256
  (`keytool -list -v -keystore <upload keystore>`). It covers a build signed
  with that key, side-loaded or from Android Studio.

**Still to do, when the app is first uploaded to Google Play:** Play re-signs
the app with its own App Signing key, so an install from Play carries a
different certificate and the links would open in the browser. Copy that key's
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
```
