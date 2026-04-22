Manage the persistent `corrections` table — the project's feedback loop.

Invocation patterns the user may use:

- `/correction add` — Capture a new correction from the just-finished (or in-progress) exchange. Either the user has pointed out a mistake, or the assistant caught itself mid-answer. Walk back through the conversation and assemble:
    - `topic` — short stable slug, e.g. `abyssal_persecutor_vs_lose_the_game_triggers`.
    - `category` — one of `card_interaction`, `rules`, `combo`, `meta`. (`meta` = assistant-behavior guidance, not a Magic fact.)
    - `incorrect_claim` — the wrong statement, verbatim or faithfully paraphrased.
    - `correct_claim` — what actually holds, with enough specificity to be self-contained.
    - `explanation` — *why* the incorrect claim was wrong. Cite CR rule number, ruling, or card text where possible.
    - `relates_to` — JSON array of card names / rule numbers the correction applies to.
    - `source` — `user_correction` (user flagged it), `self_caught` (assistant caught own error), `ruling`, or `cr_XXX`.
    - `added_by` — `user` or `assistant` (whichever party surfaced the correction).

  Insert via `INSERT INTO corrections (topic, category, incorrect_claim, correct_claim, explanation, relates_to, source, added_at, added_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`. Set `added_at` to current UTC timestamp (ISO 8601, `Z` suffix). After inserting, echo the row back to the user for confirmation.

- `/correction show [card-name-or-topic]` — List matching corrections. If an argument is given, filter via `relates_to LIKE '%<arg>%' OR topic LIKE '%<arg>%' OR incorrect_claim LIKE '%<arg>%'`. No argument = list all, newest first. Render each row as:

    ```
    [#id] <topic>  (<category>, <source>, added <added_at>)
    ✗ incorrect: <incorrect_claim>
    ✓ correct:   <correct_claim>
    why:         <explanation>
    re:          <relates_to>
    ```

- `/correction delete <id>` — Only if the user explicitly requests deletion (e.g., the correction itself was wrong, or the card text changed upstream). Confirm the ID before deleting.

Sqlite CLI reminder for ad-hoc queries:
```sql
SELECT id, topic, correct_claim FROM corrections
WHERE relates_to LIKE '%Phage the Untouchable%'
ORDER BY added_at DESC;
```

Do NOT use `/correction` to store general project notes — those belong in `memory/` or `CHANGELOG.md`. This table is specifically for *factual corrections to previously-stated assistant claims*.
