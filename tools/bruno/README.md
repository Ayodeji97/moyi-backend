# The Moyi API in Bruno

The daily loop as twelve requests, and the two that end a bond, for running it by hand:
milestone M3 is two people using the HTTP API for a week, and there is no app yet.
`scripts/moyi` is the same loop from a terminal.

1. Open this folder in [Bruno](https://www.usebruno.com) as a collection and choose the
   `local` environment.
2. Set `email` and, as a secret, `password`. Secrets stay on your machine: Bruno does not
   write them into this folder, and nothing here holds one.
3. Run the requests in order. Login keeps the tokens, Create bond keeps the bond's id, and
   Write keeps the entry's id, each as a variable the later requests use.

**Two people means two machines, or two copies of this folder opened as two collections.**
Not two environments in one collection: the tokens and the bond's id are kept as
collection variables, which every environment shares, so the second person's login would
replace the first's. The second person skips Create bond and runs Accept invite with the
code the first was given (set it as the secret `inviteCode`).

**Not yet opened in Bruno.** These files were written against Bruno's documented format
and every request in them is one the smoke run sends with curl, but the collection itself
has not been run. If a request fails to load, that is where to look first; `scripts/moyi`
has been run end to end.

**Leave bond and Block are last, and are not part of the loop.** Each ends the bond in
use for both people. Their bodies are optional and their defaults are opposite: a leave
keeps your entries unless it is asked to take them back, a block takes them back unless it
is told to keep them, and taking them back erases them for good. Each request carries its
own default as its example body, so sending it as it stands does what sending nothing
would.

Every rule is the server's. These files send requests and decide nothing.
