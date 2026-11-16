// The one script behind /verify, /reset-password and /i/<code>.
//
// These pages are what a phone shows when an emailed or shared link did not
// open the Moyi app: the app is not installed, the link was opened on a
// computer, or an in-app browser (Gmail's, for one) did not hand it over.
//
// It reads the token or invite code out of this page's own address, and does
// three things with it, none of which sends it anywhere:
//
// - points "Open in the Moyi app" at the same address on open.getmoyi.app.
//   A tap from one host to another is what makes iOS and Android open the app
//   (a link to the page's own host stays in the browser), and the app claims
//   both hosts. Without the app, open.getmoyi.app sends the browser straight
//   back here (README.md, "The open host").
// - shows the link (or the invite code) to copy into the app by hand.
// - if this page was itself served on open.getmoyi.app, moves to the same
//   address on getmoyi.app at once (below).
//
// The Content-Security-Policy in _headers forbids every request (connect-src
// falls back to default-src 'none'), and Referrer-Policy: no-referrer keeps
// the address out of the next page's Referer, so a token leaves this page only
// by being copied or by the tap that opens the app. Opening a link spends
// nothing: an email scanner that follows it gets a page and no request reaches
// the API (ADR-0018).
//
// It checks no rule of the server's. The shapes below are the ones the app's
// own link parser accepts (moyi-app DeepLink.parse); a value outside them is
// shown as an incomplete link rather than handed to the app.
"use strict";

(function () {
  var SITE = "https://getmoyi.app";
  var OPEN = "https://open.getmoyi.app";

  // Belt and braces for the open host. Its Redirect Rule sends every page back
  // to getmoyi.app before this script could run; should that rule be missing,
  // this does the same. getmoyi.app never redirects, so there is no loop.
  if (location.hostname === "open.getmoyi.app") {
    location.replace(SITE + location.pathname + location.search);
    return;
  }

  var page = document.body.getAttribute("data-link");
  var value = "";
  var path = "";
  if (page === "invite") {
    // _redirects rewrites /i/<code> with no trailing slash, so neither does this.
    var match = location.pathname.match(/^\/i\/([^/]+)$/);
    try {
      value = match ? decodeURIComponent(match[1]).trim().toUpperCase() : "";
    } catch (malformed) {
      // A mangled %-escape: say the link is incomplete rather than show nothing.
      value = "";
    }
    if (!/^[A-Z0-9]{1,16}$/.test(value)) value = "";
    path = "/i/" + value;
  } else {
    value = (new URLSearchParams(location.search).get("token") || "").trim();
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(value)) value = "";
    path = (page === "reset" ? "/reset-password" : "/verify") + "?token=" + value;
  }

  if (!value) {
    document.getElementById("found").hidden = true;
    document.getElementById("missing").hidden = false;
    return;
  }

  document.getElementById("open").setAttribute("href", OPEN + path);
  // An invite is copied as its code; verify and reset as the whole link,
  // because the app's code field takes six digits or a link with its token=,
  // and a bare token pasted there would be read as digits.
  document.getElementById("value").textContent = page === "invite" ? value : SITE + path;

  document.querySelectorAll("button[data-copy]").forEach(function (button) {
    button.addEventListener("click", function () {
      var text = document.getElementById(button.getAttribute("data-copy")).textContent;
      navigator.clipboard.writeText(text).then(
        function () { flash(button, "Copied"); },
        function () { flash(button, "Select and copy it instead"); }
      );
    });
  });

  function flash(button, label) {
    var before = button.textContent;
    button.textContent = label;
    setTimeout(function () { button.textContent = before; }, 1600);
  }
})();
