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

Write a new message **to the address the email came from**, copied from the From line (never typed:
one wrong letter writes to a stranger), and never by pressing Reply if the message sets a different
Reply-To:

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

On the server (MOYI_OPERATOR is your name; it is recorded with the note). **Copy the address from
the confirmation's From line and paste it; never type it.** It is asked for twice, and the command
stops if the two differ. Then it shows the account as stored and waits for `yes`: check that address
is the one in the email before typing it.

```sh
MOYI_OPERATOR=daniel scripts/moyi account delete "confirmed by reply from the account address on 2026-11-20"
account email (paste it from the email's From line): person@example.com
the same again: person@example.com
Open the deletion of the account at person@example.com (status ACTIVE, address confirmed)? Type yes: yes
```

**The note never holds the address** (the command refuses an `@`): the audit row names the account by
id, and is kept for 2 years after the account is gone. Say how they confirmed, and when.

`Requested: account <id> is deleted from <date> (14 days).` Then reply:

> Your Mọyì account is set to be deleted on <date>. Until then you can still cancel it in the app,
> under Bond settings, then Account. After that date your entries, photos, voice notes, name and
> email address are deleted, and backups are overwritten within 35 days.

**If the account is suspended** (the command says so), they cannot sign in to cancel, so reply:

> Your Mọyì account is set to be deleted on <date>. If you change your mind before then, reply to
> this email and we will cancel it. After that date your entries, photos, voice notes, name and
> email address are deleted, and backups are overwritten within 35 days.

`Already requested on …` means a deletion is already pending (from the app or an earlier email):
nothing changed; reply with its date the same way.

## 5. Nothing was done

`no account has that address` (or it was already deleted). Reply:

> We could not find a Mọyì account for this address, so there is nothing to delete. If you signed
> up with a different address, write to us from that one.

`never confirmed its address`: the account exists but its address was never confirmed, so a reply
from it does not show the account is theirs. Nothing was done. Reply:

> Before we can delete this account, the address on it has to be confirmed. Open the Mọyì app,
> sign in, and confirm your email with the code we send you; you can then delete the account
> yourself in the app, under Bond settings, then Account, or write to us again.

## 6. They change their mind by email

Only while the 14 days are open, and only on a message from the account's address that passes the
checks in step 3:

```sh
MOYI_OPERATOR=daniel scripts/moyi account cancel-deletion "asked to cancel by reply from the account address on 2026-11-25"
```

The same two addresses and `yes`. `Cancelled: …` then reply:

> The deletion of your Mọyì account is cancelled. Nothing has been deleted, and you can keep using
> the app as before.

`no deletion that can still be cancelled` means none is pending, or its 14 days have passed and it
has been carried out or is about to be. Nothing was done; tell them so.

## Where the address goes

Only to the database, and never on a command line: the command reads it from your terminal and
hands it to `psql` on its standard input, so it is in no process list and no shell history. It is
in the SQL text the database runs, so if a statement fails, the Postgres server log can hold it,
like any other personal data in that log. Nothing else records it.

## What is kept

As the privacy notice says: safety records without content (a report, block or suspension, by
account number) for 2 years, and the audit rows these commands write (the account's id, your name,
your note; never the email address).
