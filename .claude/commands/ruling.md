Look up rulings for a Magic card.

Card name: $ARGUMENTS

Steps:
1. Query the `cards` table for an exact match on the name. If no hit, retry with `LIKE '%name%'`. If still nothing, say so and stop.
2. Show the card's `oracle_text` and `type_line` as context.
3. Query `rulings` for that card and list all rulings in chronological order with their dates.
4. If the card has zero rulings, state that explicitly — do not invent or paraphrase rulings from general knowledge.
5. If the user's question implies a *rules interaction* (uses words like "stack", "trigger", "layer", "replacement", "priority"), also do a relevant lookup in the `rules` table and surface anything pertinent.
