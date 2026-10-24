// The one script behind /verify, /reset-password and /i/<code>.
//
// It reads the token or invite code out of this page's own address and shows
// it, with the scripts/moyi command that uses it. It sends nothing anywhere:
// the Content-Security-Policy in _headers forbids every request (connect-src
// falls back to default-src 'none'), so a token can only leave this page by
// being copied. That is also why opening a link spends nothing: an email
// scanner that follows it gets a page and no request reaches the API
// (ADR-0018).
//
// It checks no rule of the server's. The one check below is about display:
// a value is offered as part of a shell command only when it is letters,
// digits, '-' and '_', so a crafted link cannot put `$(…)` into a line
// somebody pastes into a terminal. Whether the value is a real token or code
// is the server's to say.
"use strict";

(function () {
  var page = document.body.getAttribute("data-link");
  var value = "";
  if (page === "invite") {
    var match = location.pathname.match(/^\/i\/([^/]+)\/?$/);
    value = match ? decodeURIComponent(match[1]).trim().toUpperCase() : "";
  } else {
    value = (new URLSearchParams(location.search).get("token") || "").trim();
  }

  var safe = /^[A-Za-z0-9_-]{1,128}$/.test(value);
  var commands = {
    verify: "scripts/moyi verify " + value,
    reset: "scripts/moyi reset-password " + value,
    invite: "scripts/moyi bond join " + value,
  };

  if (!safe) {
    document.getElementById("found").hidden = true;
    document.getElementById("missing").hidden = false;
    return;
  }
  document.getElementById("value").textContent = value;
  document.getElementById("command").textContent = commands[page];

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
