-- Slice C6a: the prompt library (FR-047, doc 04 "Prompt", cADR-0013, ADR-0038).
--
-- A prompt is an optional suggested sentence-starter. Never required, never inserted
-- into an entry (doc 04 glossary). The day's prompt is the same for both members of a
-- bond (the owner's Q-P1) and is chosen by a pure function of the bond, the bond-local
-- date and this library (`PromptOfTheDay`), so nothing here records what anybody was
-- shown.
--
-- There is NO prompt_impressions table, on purpose. Doc 07 and spec §7 give one:
-- (bond_id, prompt_id, shown_on, used). Written when a member opens the app, it would
-- be a log of which days each member opened it, which FR-064 forbids; read by the
-- choice, it would let what one member did move what the other is offered (c6-corpus
-- H-P1, ADR-0038). The choice needs neither. "used" is entries.prompt_id (c6-corpus P5,
-- ADR-0038).
--
-- prompts.text is the `en` text, NOT NULL: `en` is the launch locale (FR-122) and every
-- member's last fallback, so every prompt can be shown to everybody and a missing
-- translation never changes WHICH prompt a bond is offered, only its words.
-- prompt_texts holds the other locales, lower case, and refuses `en` so that there is
-- one `en` text. Doc 07's `prompts.locale`, `category` and `weight` are not here:
-- the locale is per text, the category is the audience, and nothing weights the choice.
--
-- audience is who a prompt is for (cADR-0013): CORE, shared by every bond, or one bond
-- type's overlay over it. PARENT_CHILD has no overlay; it is offered FAMILY's
-- (`states.md` §2), a mapping the application makes (`DailyPrompt`).
--
-- active: a prompt is retired, never deleted. entries.prompt_id may name it for good,
-- and an offline draft may name it after it is retired (ADR-0038 decision 8).
--
-- created_at is when the row was written, by this migration or a later one. It is a
-- fact about the library, not about any person.
--
-- The seed is placeholder wording (the owner's Q-P6): the 100-prompt library is
-- Daniel's editorial work (spec §1). Changing it is a new migration, which is a deploy:
-- FR-047's "refreshable without deploying" is a recorded gap until modules/admin
-- (the owner's Q-P4).
CREATE TABLE prompts (
    id         uuid        PRIMARY KEY,
    audience   text        NOT NULL,
    text       text        NOT NULL,
    active     boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT prompts_audience_check CHECK (audience IN ('CORE', 'COUPLE', 'FRIENDS', 'FAMILY')),
    CONSTRAINT prompts_text_check CHECK (char_length(text) BETWEEN 1 AND 200 AND text = btrim(text))
);

CREATE TABLE prompt_texts (
    prompt_id uuid NOT NULL REFERENCES prompts (id) ON DELETE CASCADE,
    locale    text NOT NULL,
    text      text NOT NULL,
    PRIMARY KEY (prompt_id, locale),

    -- Lower case, as `PromptLocale` compares; a language and at most one subtag, as
    -- `RegisterRequest` admits for users.locale. `en` is prompts.text, not a row here.
    CONSTRAINT prompt_texts_locale_check CHECK (locale ~ '^[a-z]{2,3}(-[a-z0-9]{2,8})?$' AND locale <> 'en'),
    CONSTRAINT prompt_texts_text_check CHECK (char_length(text) BETWEEN 1 AND 200 AND text = btrim(text))
);

-- entries.prompt_id has existed since V12 with nothing to point at. Both tables are this
-- module's, so the reference is the database's to hold. No ON DELETE: prompts are
-- retired, not deleted, and deleting one an entry names should fail. No index on
-- entries.prompt_id for the same reason: nothing deletes a prompt or asks which entries
-- answered one. An erasure nulls it (`Entry.erase`, ADR-0038, controller ruling 7).
ALTER TABLE entries
    ADD CONSTRAINT entries_prompt_fk FOREIGN KEY (prompt_id) REFERENCES prompts (id);

INSERT INTO prompts (id, audience, text) VALUES
    ('0199e8a0-6a00-7000-8000-000000000001', 'CORE', 'What did they make easier today?'),
    ('0199e8a0-6a00-7000-8000-000000000002', 'CORE', 'What did they do today that you would have missed if you had not been looking?'),
    ('0199e8a0-6a00-7000-8000-000000000003', 'CORE', 'What is something they said this week that stayed with you?'),
    ('0199e8a0-6a00-7000-8000-000000000004', 'CORE', 'When did you last laugh together, and what was it about?'),
    ('0199e8a0-6a00-7000-8000-000000000005', 'CORE', 'What small thing did they do today without being asked?'),
    ('0199e8a0-6a00-7000-8000-000000000006', 'CORE', 'What did they teach you, even if they did not mean to?'),
    ('0199e8a0-6a00-7000-8000-000000000007', 'CORE', 'What would today have been like without them?'),
    ('0199e8a0-6a00-7000-8000-000000000008', 'CORE', 'What do they do that you have stopped noticing, and are glad of?'),
    ('0199e8a0-6a00-7000-8000-000000000009', 'CORE', 'What did they carry today so you did not have to?'),
    ('0199e8a0-6a00-7000-8000-00000000000a', 'CORE', 'Which moment from today would you keep, if you could keep one?'),
    ('0199e8a0-6a00-7000-8000-00000000000b', 'CORE', 'What did they get right today?'),
    ('0199e8a0-6a00-7000-8000-00000000000c', 'CORE', 'What are you looking forward to doing with them next?'),
    ('0199e8a0-6a00-7000-8000-00000000000d', 'COUPLE', 'What did your partner do today that made home feel like home?'),
    ('0199e8a0-6a00-7000-8000-00000000000e', 'COUPLE', 'When did you feel most on the same side today?'),
    ('0199e8a0-6a00-7000-8000-00000000000f', 'COUPLE', 'What do you understand about them now that you did not a year ago?'),
    ('0199e8a0-6a00-7000-8000-000000000010', 'COUPLE', 'What did they give up today so the two of you could have something?'),
    ('0199e8a0-6a00-7000-8000-000000000011', 'FRIENDS', 'When did this friend show up when it mattered?'),
    ('0199e8a0-6a00-7000-8000-000000000012', 'FRIENDS', 'What does this friendship let you be that nothing else does?'),
    ('0199e8a0-6a00-7000-8000-000000000013', 'FRIENDS', 'What did they remember that you had forgotten you told them?'),
    ('0199e8a0-6a00-7000-8000-000000000014', 'FRIENDS', 'What is a joke only the two of you would understand?'),
    ('0199e8a0-6a00-7000-8000-000000000015', 'FAMILY', 'What did they pass on to you that you are glad to have?'),
    ('0199e8a0-6a00-7000-8000-000000000016', 'FAMILY', 'What did they do for the family this week that nobody thanked them for?'),
    ('0199e8a0-6a00-7000-8000-000000000017', 'FAMILY', 'What is a memory of them you want to keep?'),
    ('0199e8a0-6a00-7000-8000-000000000018', 'FAMILY', 'How did they make today a little lighter?');
