# Deleting an account asked for by email

getmoyi.app/delete-account promises a way to delete an account without the app (Google Play
requires one):

> Email hello@getmoyi.app from the address on your account, with the subject Delete my account.
> We reply to that address to confirm it is you, then delete the account within 30 days.

This is how that promise is kept. The command opens the same request the app's "Delete your
account" opens (ADR-0058, amended 2026-11-16): 14 days in which the person can still cancel in the
app, then the deletion job carries it out. **Start it within 14 days of the first email**, so the
deletion lands inside the 30 days promised.

## 1. The email arrives

Subject "Delete my account", or anything that clearly asks for it. Nothing is done yet. A From
line can be forged, so the email alone proves nothing.

## 2. Reply to the address, not to the reply-to

Write a new message **to the address the email came from**, typed or copied from the From line,
never by pressing Reply if the message sets a different Reply-To:

> Subject: Confirm: delete your Mọyì account
>
> We received a request to delete the Mọyì account for this address. If it was you, reply to this
> email with "Yes, delete it". If it was not you, ignore this email and nothing will happen.

Do not say whether an account exists for the address: only its owner reads this reply, but keep it
the same for every request.

## 3. The confirmation comes back

It must come **from the same address**, and in Gmail "Show original" must say SPF and DKIM `PASS`.
If it does not, or never comes, do nothing; after 30 days, close it with no action.

## 4. Run the command, then reply

On the server (MOYI_OPERATOR is your name; it is recorded with the note):

```sh
MOYI_OPERATOR=daniel scripts/moyi account delete "confirmed by reply from the account address on 2026-11-20"
account email: person@example.com
```

`Requested: account <id> is deleted from <date> (14 days).` Then reply:

> Your Mọyì account is set to be deleted on <date>. Until then you can still cancel it in the app,
> under Bond settings, then Account. After that date your entries, photos, voice notes, name and
> email address are deleted, and backups are overwritten within 35 days.

`Already requested on …` means a deletion is already pending (from the app or an earlier email):
nothing changed; reply with its date the same way.

## 5. No account has that address

`no account has that address` (or it was already deleted). Nothing was done. Reply:

> We could not find a Mọyì account for this address, so there is nothing to delete. If you signed
> up with a different address, write to us from that one.

## What is kept

As the privacy notice says: safety records without content (a report, block or suspension, by
account number) for 2 years, and the audit row this command writes (the account's id, your name,
your note; never the email address).
