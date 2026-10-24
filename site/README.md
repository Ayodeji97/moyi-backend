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
