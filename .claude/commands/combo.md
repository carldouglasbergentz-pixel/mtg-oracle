Find combos involving a card or set of cards.

Input: $ARGUMENTS

Steps:
1. Parse the input — may be one card name or several (comma-separated).
2. **One card:** find all combos containing it via `combo_cards`.
3. **Multiple cards:** find combos that contain *all* of them:
   ```sql
   SELECT combo_id FROM combo_cards
   WHERE card_name IN (...)
   GROUP BY combo_id
   HAVING COUNT(DISTINCT card_name) = <number of cards>;
   ```
4. For each matching combo, output:
   - The cards involved (from `combo_cards`)
   - Color identity
   - Prerequisites (from `combo_prerequisites`)
   - The result(s) (from `combo_results`)
   - The steps in order (from `combo_steps`)
5. Sort combos by **fewest cards first** (simpler combos at top).
6. If there are more than 10 matches, show the top 10 and report the total count.
7. Always remind the user that Spellbook does not cover every possible combo — homebrew or obscure interactions may not appear here.
