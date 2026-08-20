# Report style

House style for the deck-analysis reports in `docs/*-report.html`. Extracted
after the second report was written from scratch in the wrong language with a
different palette, because the first one was the only record of the convention
and nobody had written it down.

Reports so far:

| file | subject | artifact |
|---|---|---|
| [`uw-canlander-report.html`](uw-canlander-report.html) | 9 winning UW Canadian Highlander lists | https://claude.ai/code/artifact/6b0575c0-c720-4e31-b02c-506f277f3491 |
| [`elminster-dc-report.html`](elminster-dc-report.html) | Elminster vs. 4 placed Duel Commander lists | https://claude.ai/code/artifact/e3e2e0a3-eeea-4b1e-948b-9cbc068414ca |

## The two rules that were broken, so they go first

- **Reports are written in Swedish.** Every other document here — CHANGELOG,
  CLAUDE.md, README, project-plan, commit messages, code comments — is English,
  because those are code-facing. A report is the one artefact written to be
  *read by the user*, and the user writes Swedish. Card names stay in English
  (they are proper nouns and the database is English), and so do the game terms
  the user himself uses in Swedish: counterspells, removal, threats, sweepers,
  card advantage, on curve, wincon, mana value.
  **The `<title>` stays English** — it is the artifact's name in the gallery,
  and `Azorius on Curve` set that pattern.
- **A report lives in `docs/` AND as an artifact.** The file is the
  version-controlled source; the artifact is the link the user can open on a
  phone. Publishing one without committing the other loses either the history
  or the readability. Link the artifact URL from
  [`project-plan.md`](project-plan.md) next to the phase that produced it.

## Palette and type

Copy the `<style>` block from either existing report verbatim. It is Azorius by
design — limestone paper, ink with a blue bias, lapis accent, restrained gold
for the one thing that needs emphasis — and the tokens are identical across
both files.

```
--paper:#F5F4F1  --surface:#FFFFFF  --sunk:#EDEBE6
--ink:#16181D    --ink-2:#42474F    --ink-3:#6E747E
--rule:#DCD9D2   --rule-2:#C6C2B9
--lapis:#2C4B8F  --lapis-wash:#E3E8F4
--gold:#8A6A34   --gold-wash:#F1E9D9
--good:#2F6B51   --warn:#8A6A34     --crit:#8F3630
```

Type is **Spectral** for body (a serif body is the point — these are read, not
skimmed), **IBM Plex Sans** for labels, eyebrows, tables and figures, **IBM
Plex Mono** for mana costs and list identifiers. One Google Fonts link.

Light is the primary palette. Both dark paths redefine **only** tokens: the
`@media (prefers-color-scheme:dark)` block is guarded with
`:root:not([data-theme="light"])`, and `:root[data-theme="dark"]` repeats it so
an explicit toggle wins in either direction.

## Structure

```
<title>                       English, short noun phrase, stable across redeploys
<link> Google Fonts
<style>                       the shared sheet, inlined (artifacts are self-contained)
header.mast > .wrap           eyebrow, h1, .dek, .meta, .figs
.wrap > .layout
  nav.toc                     sticky, collapses to a row under 900px
  main
    section#id
      h2 > span.h2n           label above, sentence below
      .lede                   one paragraph stating the finding
      .scroll > table         every table, always, so the page never scrolls sideways
      .cap                    what the table does not say
      .note[.gold|.plain|.crit] > .nt
footer > .wrap                provenance
```

`.figs` is the key-figure strip in the masthead: three or four numbers that
carry the whole report. `.note.crit` is for a correction to something the
assistant previously claimed — those belong in the report, not only in the
`corrections` table.

## Content conventions

- **State the method before the numbers.** Both reports have a `Metod` section
  saying what is modelled and what is not. A reader who does not know that
  `{X}` is priced by convention cannot argue with the curve.
- **Verdicts are against the range, not the mean.** Being two cards off an
  average that spans five is noise; stepping outside a range nobody left is a
  choice. Say which.
- **Mark the strength of every trend claim.** Small reference sets are the norm
  here. The Elminster report carries a `p` column — the probability of the
  observed count arising by chance — and labels rows *signal* / *svag* / *brus*
  accordingly. A finding that does not survive its own sample size gets said so
  in the same table.
- **Own the corrections.** Where the data contradicts earlier advice, the report
  says which advice and why, by name.
- **Close with limits.** A `Vad detta inte kan säga` section, always.

## Before publishing

Cross-check every number against the source data programmatically, not by
reading. The Elminster report was verified with 128 assertions against the
thirteen decklists; one mismatch surfaced — a rounded value being rounded again
in the CLI, printing `-6p` for a true `-5.5pp`. That is the class of error
reading cannot catch.
