# The Moyi API in Bruno

The daily loop as twelve requests, for running it by hand: milestone M3 is two people using
the HTTP API for a week, and there is no app yet. `scripts/moyi` is the same loop from a
terminal.

1. Open this folder in [Bruno](https://www.usebruno.com) as a collection and choose the
   `local` environment.
2. Set `email` and, as a secret, `password`. Secrets stay on your machine: Bruno does not
   write them into this folder, and nothing here holds one.
3. Run the requests in order. Login keeps the tokens, Create bond keeps the bond's id, and
   Write keeps the entry's id, each as a variable the later requests use.

Two people means two environments (copy `local`), or two machines. The second person skips
Create bond and runs Accept invite with the code the first was given.

Every rule is the server's. These files send requests and decide nothing.
