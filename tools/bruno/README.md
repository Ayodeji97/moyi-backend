# The Moyi API in Bruno

The daily loop as twelve requests, the four that read the archive and keep a bookmark, the
two that leave a reaction and take it back, the two that end a bond, the two that search
the archive and read this date in earlier years, the one that reads the milestones, and the
two that give the device you signed in from a push token and take it away, for running it by hand:
milestone M3 is two people using the HTTP API for a week, and there is no app yet.
`scripts/moyi` is the same loop from a terminal.

1. Open this folder in [Bruno](https://www.usebruno.com) as a collection and choose the
   `local` environment, or `production` for https://api.getmoyi.app (the M3 week; every
   request there touches real data).
2. Set `email` and, as a secret, `password`. Secrets stay on your machine: Bruno does not
   write them into this folder, and nothing here holds one.
3. Run the requests in order. Login keeps the tokens, Create bond keeps the bond's id,
   Write keeps the entry's id, and Days keeps the newest day's date and your partner's
   entry on it, each as a variable the later requests use.

**Two people means two machines, or two copies of this folder opened as two collections.**
Not two environments in one collection: the tokens and the bond's id are kept as
collection variables, which every environment shares, so the second person's login would
replace the first's. The second person skips Create bond and runs Accept invite with the
code the first was given (set it as the secret `inviteCode`).

**First opened 2026-10-27**, in Bruno 4.3, against production as an existing account:
Login, List bonds, Today, Streak, Days, Day (200, then 304 on the second send) and
Milestones ran without an error. That run found that List bonds kept no `bondId`, so a
session in an existing bond could reach none of the bond's routes (fixed in #93). The
other requests are still unrun in Bruno; `scripts/moyi` sends the same requests and has
been run end to end, so a failure there is most likely in the file, not the server.

**Leave bond and Block are not part of the loop.** (Search, On this day and Milestones
come after them only because they were added later; all three only read.) Each ends the bond in
use for both people. Their bodies are optional and their defaults are opposite: a leave
keeps your entries unless it is asked to take them back, a block takes them back unless it
is told to keep them, and taking them back erases them for good. Each request carries its
own default as its example body, so sending it as it stands does what sending nothing
would.

Every rule is the server's. These files send requests and decide nothing.
