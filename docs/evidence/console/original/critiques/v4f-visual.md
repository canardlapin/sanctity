## Scores

| dimension | score | justification |
|---|---|---|
| typography | 8 | The system is unchanged and still disciplined. The v4d hyphen-split role chips are fixed: wrapped role labels went from 2 to 0 on release@390 and from 10 to 0 on assessment@390 (measured). Query paths at 390 are now 2 lines, 36px, down from 6–7 lines and 136–156px. Carried over: rule-family names at 390 still break at hyphens ("bids-mri-header-" / "consistency-v1"). The fact-list `dt` uses `overflow-wrap:anywhere`, so "timingMetadata.RepetitionTime.origi" / "n" breaks mid-word at 1440. That also happened in v4d. |
| color | 9 | One indigo accent, with amber and red kept for findings. The outlined delta tags stay quiet. The new no-op sidecar notice is an amber wash and the nameless-path refusal is a red wash, both consistent with the token set. Dark mode is coherent on every page captured. |
| layout | 7 | The stacked phone tables are a real gain. Desktop composition got worse: on the correction page at 1360 and 1440 the columns now end at 1158 and 1577, a 419px gap (v4d 342, v3 35). In the stacked grant block, the hash chips stretch to 236px with centred text and the Revoke button starts at x=29 while the card content starts at x=33. |
| hierarchy | 7 | Regression: on the blocked preview, "No longer reported" (a NOT EVALUATED warning) now comes before the error that blocks acceptance. The first error moved from y=826 to y=1098 at 1360, and from y=956 to y=1362 at 390. On the admissible preview, the findings the change *introduces* (NEW ×3) now sit below the resolved ones. The summary line still reads "3 new, 2 unchanged, 2 no longer reported", in the opposite order to the sections. |
| components | 8 | The grant and selection tables become labelled blocks, a sound responsive pattern. The "N occurrences" pill, the key-facts `dl` and "Same value, new source" are good additions. Weak: "2 occurrences" is wider than "× 2", so on desktop the RESOLVED and NEW tags wrap onto their own line while UNCHANGED stays inline. In v4d all four were inline. Hash chips stretch inside the stacked block. The content-changes table did not get the stacked treatment ("task-nback_" / "bold.json", "Same size, new" / "content" at 390). |
| function | 9 | 36 captures (9 pages × 1360/390 × light/dark): all reached `ready`, with 0 page or console errors and 0 horizontal overflow. Revoke is now on screen at 390 (x=29, w=332; v4d x=540 off-canvas) and its accessible name is "Revoke grant d6768b0f". A nameless ".json" is refused with `aria-invalid=true`, `aria-describedby` set and focus returned to the field. The no-op sidecar warning appears. Gate log exit 0. |
| themes | 9 | Dark was checked on the blocked preview at 1360, the blocked preview at 390, access at 390, and the add-sidecar preview at 390. The amber NOT EVALUATED fill, the red error card and the red `!` diff line all read clearly. No glare. |
| mobile | 8 | Query at 390: 1485 → 1089px, paths 2 lines, the empty Session column dropped. Access at 390: Revoke reachable, full width. Remaining: the header still pushes the tabs to y=525. The correction page at 390 grew to 3325px (+74), and its first error now sits 406px lower. The key facts at 390 stack on two lines with values that don't align (1.5 at about x=213, 2 at about x=218). |
| identity | 8 | The vocabulary is still coherent and grows cleanly: "Same value, new source", "N occurrences", "Authored RepetitionTime / Header volume spacing". There is still no signature move beyond the cascade pips and dashed digest chips. |
| fidelity | 8 | Gains: the TR mismatch now shows the compared values (1.5 s vs 2 s), and grouped rows read "rows 50, 51" instead of "row …". Gaps: (a) The NEW grouped TR mismatch, the one the 1.8 s correction introduces, shows no compared values, because key facts render only when a group has exactly one file (`app.js` line 607). (b) A NOT EVALUATED outcome still sits under "No longer reported", and is now the first thing on the blocked card. (c) The footnote "Only files whose effective values change are listed" sits directly under a row tagged "Same value, new source". |

## Previous round

v4d visual issues, re-checked on v4f:

1. **Revoke off-canvas at 390: fixed.** The grants table renders as a block. Revoke is at x=29, w=332, y=1149, inside the viewport, and is named "Revoke grant …" (probe.js). New nits are in Issue 4.
2. **Query table shreds paths at 390: fixed.** Links are 36px tall (v4d 156/136). The all-"—" SESSION column is gone at every width (headers are now Participant/File/Release). Paths break after `_`, which reads acceptably.
3. **Role label breaks at hyphen: fixed.** 0 wrapped role chips on all 36 captures (v4d: 2 + 2 + 10 + 10).
4. **Correction column balance: worse.** The gap is 419px at 1360 and 1440 (v4d 342). The left column is not sticky.
5. **Grouped elision reads as truncation: fixed.** "rows 50, 51 column 1".
6. **NOT EVALUATED under "No longer reported": not addressed.** The heading is unchanged, and the section now leads the card (Issue 1).
7. **Header at 390 (three identity rows): not addressed.** Tabs are still at y=525.
8. **Query page on desktop: partly addressed.** The Session column is dropped. The underlined mono links and the floating "New query" button remain.
9. **Live B0 row: not re-checked.** No live shots this round.

## Claims

- "Grouped row findings list their rows; counts read 'N occurrences'": **verified** (assessment, correction).
- "TR mismatch shows the compared values": **verified** for the single-file case (assessment, inspector, the UNCHANGED card in the preview). **Not shown** for the grouped NEW case (Issue 2).
- "Preview: 'No longer reported' comes first": **verified** (overline order, probe.js). Judged a hierarchy regression on blocked previews (Issue 1).
- "Source-only changes tagged 'Same value, new source'": **verified** by driving a new `sub-04/sub-04_task-rest_bold.json` with TR 1.5 (drv/same-value-1440.png, drv/same-value-390.png).
- "A new sidecar that changes nothing gets a warning": **verified** with `{ }` (drv/noop-sidecar-1440.png). It is an amber notice in the left card, "0 files affected".
- "A nameless '.json' path is refused": **verified** (drv/nameless-json-1440.png), with `aria-invalid=true` and focus on `#c-new`.
- "Phones: grant and selection tables become labelled blocks; all-empty Session dropped": **verified** (access-390, query-390, light and dark).
- "Each Revoke names its grant": **verified** (accessible name "Revoke grant d6768b0f").
- "'Correct the current head' keeps edited content", "Tree fold follows viewport changes", "Rule families with blocking errors start open", "'Choose at least one action' focuses the group", "revoke-by-id error described", "Demo resolves subject-level sidecars": **not driven by me.** The add-sidecar preview shows RESOLVED on the sub-04 rest TR mismatch, which is consistent with subject-level inheritance.
- "Gate v4f exit 0": **verified** from the log tail only (not re-run).
- "Probes 35–44 fail on v4e and pass on v4f": **unverified.**

## Issues

1. **On a blocked preview, the blocking error is no longer first (medium, hierarchy and fidelity, regression).**
   - In v4f, "No longer reported" leads, so a NOT EVALUATED warning sits above "InconsistentMetadata · Blocks acceptance".
   - The first error moves from y=826 to y=1098 at 1360, and from y=956 to y=1362 at 390 (probe.js).
   - On an admissible preview, the NEW findings a reviewer must weigh also sit below the resolved ones.
   - Evidence: v4f-visual-work/shots/correction-blocked-1360-dark.png, cb390-top-dark.png, shots-v4d/correction-blocked-1360-light.png.
   - Minimal remedy: order the sections Errors (new or unchanged) → New warnings → No longer reported → Unchanged. A simpler alternative is "Findings after this change" first whenever any error is present. Also split NOT EVALUATED out under its own heading, such as "Not re-evaluated".
2. **The grouped NEW TR mismatch hides its compared values (medium, fidelity).**
   - The one finding the 1.8 s correction creates ("3 occurrences", NEW) shows no Authored/Header values.
   - The UNCHANGED single-file mismatch beneath it does show them.
   - Cause: `const facts = !many && fs.length === 1 && detail ? detail(f) : null` (app.js line 607).
   - Remedy: when every file in the group has identical key facts, render the `dl` once. Otherwise append them per `where-row`, e.g. `1.8 s vs 2 s`.
3. **Delta tags wrap inconsistently on desktop (low-medium, components, regression).**
   - With the "N occurrences" pill, the RESOLVED and NEW tags drop onto a second line while UNCHANGED stays inline. In v4d all four were inline at 1360.
   - Evidence: shots/correction-1360-light.png vs shots-v4d/correction-1360-light.png.
   - Remedy: put the delta tag first in the row (before the code), or use the shorter "×2" with `title="2 occurrences"`. Either keeps the most important tag in a fixed place.
4. **Stacked grant block polish (low, layout).**
   - The GRANT and RECIPIENT hash chips stretch to 236px with centred text, while SCOPE and ACTIONS are left-aligned plain text.
   - The block content and Revoke start at x=29, against x=33 for the card heading and the Revoke-by-id field.
   - The actions read "Read content / Read metadata", the reverse of the form order.
   - Evidence: shots/access-390-light.png, acc390-dark.png.
   - Remedy: `justify-self:start` (or `width:auto`) on chips in stacked mode, matching inline padding, and the form's action order.
5. **Correction columns more unbalanced (low, layout).** The gap is 419px at 1360 and 1440. Remedy, as before: a sticky left column, or move Content changes under Candidate on wide screens.
6. **The footnote contradicts the "Same value, new source" row (low, fidelity copy).** "Only files whose effective values change are listed" sits under a row whose value did not change. On the no-op case it repeats beneath the warning. Remedy: "Files whose effective value or its source changes are listed", and hide the footnote when 0 files are affected.
7. **The no-op warning sits far from the decision (low, taste).** The amber notice is in the left card. The Candidate card still shows a green "Admissible" and an enabled Accept, with nothing nearby. Remedy: add one muted line under Accept, such as "This sidecar changes no effective metadata."
8. **Content-changes table at 390 (low).** It is the only table left unstacked, so the path and "Same size, new content" wrap. Remedy: apply the same labelled-block pattern.
9. **Carried over.**
   - The fact-list `dt` breaks mid-word ("origi/n"). Fix with `overflow-wrap:normal` plus `<wbr>` after dots.
   - An orphan "Resolved from …" line ends Effective metadata with no label.
   - Header at 390, tabs at y=525.
   - Rule-family names break at hyphens at 390.

## Regressions

- **Blocked preview: the blocking error moves 272px lower at desktop and 406px lower at 390**, behind a NOT EVALUATED warning (Issue 1). This comes from the new section order.
- **Delta tags no longer inline for RESOLVED and NEW at 1360 and 1440** (Issue 3). This comes from the "N occurrences" wording.
- **Correction column gap 342 → 419px** (Issue 5).
- **Page lengths:** assessment@1360 948 → 974, assessment@390 1593 → 1642, correction@390 3251 → 3325, access@390 1303 → 1391. These are expected costs of key facts and stacked blocks. Query@390 improved, 1485 → 1089.
- No functional regressions found: 0 errors and 0 overflow on all 36 captures and 7 driven states. Release, relationships, connect and empty-release heights are identical to v4d.

## Proposals

- **One ordering rule for every finding list:** severity, then delta (Blocks → New → Unchanged), with "No longer reported" and "Not re-evaluated" last as collapsed groups. This fixes Issue 1 and keeps the page calm.
- **Key facts at group level** (Issue 2). Comparison findings are where a curator decides, so show the numbers wherever the finding appears.
- **Finish the narrow-table pattern** on Content changes, and make stacked chips intrinsic width. Then every table behaves the same at 390.
- **One identity line in the header at 390** is still the cheapest mobile gain (carried over).

Evidence: critiques/v4f-visual-work/
- shots/: 36 v4f captures, named `<page>-<1360|390>-<light|dark>.png`.
- shots-v4d/: the same 36 for v4d.
- cap-v4f.json and cap-v4d.json: metrics.
- probe.js output: section order, firstError y, column ends, tag placement, grant geometry.
- probe2.js: inspector fact breaks, nameless-path a11y.
- drv/: same-value, no-op sidecar, nameless path, add-sidecar 390 dark, inspector-rest.
- Clips: a390.png, c390-*.png, cb390-top-dark.png, acc390-dark.png.

Unverified:
- Real screen reader.
- Safari and Firefox.
- Live backend (no live run this round).
- Viewport-change tree fold.
- "Correct the current head" keep-edits note.
- Error-family default-open on a dataset with blocking errors.
- Access "Choose at least one action" and revoke-by-id error states (not driven).
