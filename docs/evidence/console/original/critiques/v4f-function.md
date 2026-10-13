## Scores

| dimension | score | justification |
|---|---|---|
| typography | 8 | Unchanged. Key facts read "1.5 s" and "2 s" with units, in light and dark. One text defect: a grouped message across several files reads "rows listed by filecolumn 1", with the space missing. |
| color | 8 | Error and warning chips, the "Same value, new source" tag and the warn notices stay distinct in light and dark. axe reports 0 violations in every state I tested. |
| layout | 8 | No page overflow at 390, at 720@2x (200% zoom) or at 1440, in light or dark, in Chromium and Firefox. The decision panel is sticky and 1544px tall at 1440, but scrolling still reaches its last finding. |
| hierarchy | 8 | "No longer reported" now comes before "Findings after this change". The TR mismatch shows its compared values in place. Rule families start folded unless an error is present, and the summary says "1 with findings". |
| components | 8 | Each Revoke now names its grant ("Revoke grant 79195308"). On phones the stacked grant and selection tables keep table, columnheader and cell roles in Chromium's real AX tree. The CSS labels are also read as part of each cell ("GRANT Copy grant id …"). |
| function | 7 | Four v4d defects are fixed and verified in 3 engines, including the major new-sidecar path loss. The fix for v4d issue 7 adds a lost-update risk. A carried draft replaces the new head's file as a whole. If a colleague changed the same file, their accepted change is reverted. The preview gives no warning at the decision step. Crossing 980px drops focus to `<body>`. |
| themes | 8 | Dark mode and reduced motion are clean: 0 running animations, and 0 axe violations on the assessment, preview, events inspector, access and selection views. Only the known `pre[aria-label]` incomplete remains. |
| mobile | 8 | Stacked grant and selection tables work at 390 and 720@2x. Revoke is 332px wide and on screen. Keyboard reach is Revoke → Cancel (focused) → Shift+Tab to Confirm, and Escape returns to Revoke. The fold now follows the viewport, but each crossing re-renders: focus, the filter text and open disclosures are lost. |
| identity | 8 | Unchanged. |
| fidelity | 8 | Row lists are back ("rows 50, 51"; per file "rows 47, 48, 49"). The single-file TR mismatch shows authored and header values. Two gaps: a grouped multi-file TR mismatch shows no compared values, and after browser Back the carried-draft note names the wrong origin release. |

## Previous round

| v4d issue | status in v4f | evidence |
|---|---|---|
| 1. M: editing the new-sidecar path discards the candidate | **Fixed.** I typed `sub-03/sub-03_bold.json` over the prefilled path by keyboard. The preview stays, and the hash becomes `?path=sub-03/sub-03_bold.json`. Focus goes to `preview-head`, and there is 1 POST. Back to edit keeps the path and the text. Verified in Chromium and WebKit; Firefox also reaches the preview (my select-all key was a no-op there, so its path differed). | `a-newsidecar.chromium.out`, `shots/a-edited-path-preview-*.png` (the Firefox and WebKit runs printed to the console only; the .out files were cut short) |
| 2. m: grouped rows drop row numbers | **Fixed**, with a new text glitch (Issue 3). Single file: "…review timing alignment rows 50, 51 column 1". The events inspector shows the same. Several files: each file lists its rows. The count reads "2 occurrences". | `c-assess.chromium.out`, `c2-multifile.chromium.out` |
| 3. m: error association gaps | **Fixed.** No actions: focus moves to `#act-ReadMetadata` and the fieldset gets `aria-describedby=g-act-err` (Chromium and Firefox). The attribute is removed once a box is checked. The alert text stays visible after it is fixed, which is cosmetic. Revoke-by-id has `aria-describedby=revoke-err`, and an unknown id shows "Grant 00000000 was not revoked…" with focus on `#revoke-id`. | `d3-actions.out`, `d-access.chromium.out`, `d2-revoke-id.chromium.out` |
| 4. m: `.json` passes | **Fixed.** `.json`, `sub-03/.json`, `sub-03/func/.bold.json` and `..json` are refused client-side with `aria-invalid` and focus on the path. Edge paths that still pass are covered by Issue 5. | `a-newsidecar.chromium.out` |
| 5. m: flagged families collapsed while the comment says open | **Resolved by intent.** The comment now matches the behaviour: only families with Error findings open. In the demo all families start folded, with a "1 with findings" chip. I could not reach an Error-bearing head in the demo. | `c-assess.chromium.out` |
| 6. m: fold evaluated once | **Fixed functionally, with focus loss** (Issue 2). Crossing 980px in either direction refolds or unfolds after one re-render. Rapid toggling (5 crossings) ends with one workspace. | `h-fold.{chromium,firefox}.out` |
| 7. m: "Correct the current head" drops the edited content | **Fixed, but over-applied** (Issue 1). The text and reason carry over with a warn note. | `e-carry.chromium.out`, `f-blocked-conflict.firefox.out` |
| 8. m: accessibility leftovers | **Partly fixed.** Revoke names its grant: fixed. Still open: `pre.linediff[aria-label]` (axe serious incomplete in every preview), tab names "Assessment 4 items" from `span.count[aria-label]`, revoked provenance in `title` only, and Back focusing `main#main`. | `l-leftovers.out`, `i-sweep.*.out`, `h-history.chromium.out` |

## Claims

| claim (changes-v4f) | verdict |
|---|---|
| Grouped rows list rows, per file | **True** in the browser. There is a "filecolumn" spacing bug (Issue 3). |
| TR mismatch shows the compared values | **True for a single file**: "Authored RepetitionTime 1.5 s / Header volume spacing 2 s" is exposed as a dl term/definition. **Not shown** when the mismatch groups several files (the TR 1.4 preview gives "3 occurrences" with no values) or in "No longer reported". |
| Preview: "No longer reported" first; "Same value, new source"; warning when a new sidecar changes nothing; `.json` refused | **True.** A `sub-01/…_bold.json` with RepetitionTime 1.5 is tagged "SAME VALUE, NEW SOURCE". The no-op warning appears for `sub-99/…`, `README.json`, `x.json.json` and others. |
| "Correct the current head" keeps the edited content, with a note | **True. The note appears only on the Edit step** and is gone from the preview where the decision is made (Issue 1). |
| Phones: tables become labelled blocks, Revoke full width; empty Session column dropped | **True.** No Session header appears in the demo selection. Chromium's AX tree keeps the table semantics (see Components). |
| Tree fold follows viewport changes | **True.** The gate checks only the fold count, not focus (Issue 2). |
| Access error wiring; contextual Revoke names | **True.** |
| Demo resolves sidecars by BIDS inheritance | **True.** `sub-03/sub-03_bold.json` changes EchoTime on both sub-03 bold runs. `sub-04/func/sub-04_task-rest_bold.json` resolves the sub-04 mismatch. |
| Gate exit 0 | **Confirmed** (`logs/gate-v4f.log`, 47 PASS lines). I saw 0 page or console errors in any probe. |

## Issues

Severity: M = major, m = minor.

1. **M (function/fidelity, lost update): a carried draft silently reverts a colleague's accepted change to the same file.**
   - Repro (`e-carry.chromium.out`, `shots/e-carry-*-same-file-*.png`):
     1. On release 9d4b2e61, correct `task-nback_bold.json` (EchoTime 0.03 → 0.031) and prepare the preview.
     2. Meanwhile, another principal accepts FlipAngle 52 → 60 on the same file.
     3. Accept → 409 → "Correct the current head" (by keyboard) → Prepare → Accept.
   - Result:
     - The new head's file has `"FlipAngle": 52`, so the colleague's 60 is gone.
     - The edit step shows only the generic note "This content was written against release 9d4b2e61 … review the preview's line changes".
     - The preview has **no notice at all** (preview notices: []). It lists "FlipAngle 60° → 52°" on 4 images as if it were part of this correction.
   - The other-file scenario is correct: only the EchoTime lines change.
   - Cause:
     - `correctionView` carries `c.text` whenever the base differs (app.js 713).
     - `load` shows `c.text || t` (the new head content `t` is ignored).
     - The base-release content the draft was written against is not kept, so nothing can detect an intervening change.
   - Minimal remedy:
     - Keep the draft's original content (`c.original`) in the carry.
     - On the new head, if `t === carriedOriginal`, carry silently.
     - Otherwise, do not prefill. Show "This file changed in release X since your draft" with the head content, plus the draft in a disclosure or a JSON key-level rebase. At minimum, keep a warning in the preview that names the fields whose before-value came from the intervening release.
2. **m (a11y/mobile): crossing 980px re-renders the release view and drops focus to `<body>`.**
   - Repro (`h-fold.{chromium,firefox}.out`): open a file at 1440 and focus the tree filter after typing "nback", or focus a button in the inspector. Resize to 390 or 900.
   - Result:
     - Focus goes to `body` and the filter is cleared to "".
     - An opened "recorded facts" disclosure closes.
     - The scroll position jumps (300 → 736 in Firefox).
   - The same happens when widening.
   - Real triggers: browser zoom to 200% on a 1440 window (720 CSS px), iPad rotation and window snapping. All of them hit keyboard and magnifier users.
   - Remedy: render both forms and switch them with CSS, so there is no re-render. Or before re-rendering, record focus by `fk`, give `#tree-filter` an `fk`, keep its value in state, and move focus to the fold summary when the focused item is folded away.
3. **m (typography/fidelity): "rows listed by filecolumn 1".** In multi-file groups the regex `\b(rows|columns) [\d, ]+` also eats the space before "column" (app.js 613). Repro: set the group TR to 1.4 and look at the preview's EventsOutsideWindow × 11 (`c2-multifile.chromium.out`). Remedy: `[\d, ]*\d` or `\d+(?:, \d+)*`.
4. **m (fidelity): after browser Back the carried-draft note names the wrong release.** After "Correct the current head", press Back to the old release's correction page. The draft is carried again, and the note reads "This content was written against release 09f6f79c" (the head) while the user is on 9d4b2e61, where it was written (`f-blocked-conflict.firefox.out`). Remedy: keep the first `carriedFrom` and do not overwrite it.
5. **m (function): some structurally odd new-sidecar paths still reach a candidate.**
   - Examples: `sub-03/func/sub-03_task-nback_run-1_bold.json/x.json` (uses an existing file as a directory), `sub-03/ .json` (a blank stem), ` sub-03/x.json` (trimmed, OK) and `sub-03/a b.json`.
   - The no-op warning now covers them, and the demo accepts them. The real backend's response is unverified.
   - Remedy: refuse a path whose directory prefix is an existing file, and a basename whose stem is blank after trimming.
6. **m (fidelity): key facts are missing for grouped and resolved TR mismatches.** The TR 1.4 preview gives "MriRepetitionTimeMismatch 3 occurrences NEW" with the three paths and no authored/header values. Only the "Changed: … 1.5 → 1.4" cause line appears. The resolved sub-04 mismatch in "No longer reported" also has no values. Remedy: show the facts per file in `where-row`.
7. **m (a11y): stacked cells read their label twice.**
   - In Chromium's AX tree at 390, a cell is named "GRANT Copy grant id …" and "PARTICIPANT sub-01". The `::before` label is part of the name, and the columnheader still exists.
   - A screen reader will say "Participant, PARTICIPANT sub-01".
   - Remedy: `content: attr(data-label) / ""`, the alt-text form, which hides the label from the AX tree.
8. **m (a11y), carried over:** `pre.linediff[aria-label]` (axe serious incomplete in every preview, all engines), tab names "Files 60 items", revoked time in `title` only, and Back focusing `main#main`. Also in WebKit, Confirm and Cancel wrap onto two lines in the stacked row (603 vs 641 px top) while Chromium keeps them side by side. This is cosmetic.

## Regressions

- I found no lost functionality in the flows I tested:
  - the new-sidecar flow, preview, Back to edit, accept, and the blocked preview with disabled Accept and the flagged "!" line
  - revoke arm, Escape and confirm by keyboard at 390, 720@2x and 1440
  - double Enter sends 1 POST
  - the access round trip with 1.5 s latency gives 1 → 2 → 3 rows
  - history Back and Forward over tabs and files
- Issue 1 is new in v4f and comes from the issue 7 fix.
- Issue 2 is new in v4f and comes from the issue 6 fix.
- Issue 3 is new in v4f.

## Proposals

1. Make the carry conditional on the file being unchanged between the draft's base and the new head (Issue 1). This is a data-integrity rule for a provenance system, not polish.
2. Make the fold CSS-only, or preserve focus and filter state across the media-query re-render (Issue 2).
3. One-line fixes: the "filecolumn" regex, keeping the first `carriedFrom`, `content: attr(data-label) / ""`, and per-file key facts.
4. Finish the old accessibility leftovers: `role="region"` on the diff, visually hidden count text, and visible revoked provenance.

**What I ran** (all in `critiques/v4f-function-work/`, static server on 127.0.0.1 from that dir's port, demo transport, real Playwright keyboard and mouse):
- `a-newsidecar` (3 engines): keyboard path edit, 19 edge paths.
- `c-assess`, `c2-multifile`: grouping, key facts, family state, preview order, same-value tag, multi-file rows.
- `e-carry`: conflict with a same-file and an other-file colleague change, through to the final head file content.
- `f-tables` (Chromium, Firefox and WebKit at 390; Chromium and Firefox at 720@2x dark), `f2-webkit-alttab` (Option+Tab) and `g-axtree` (Chromium CDP AX tree at 390 vs 1440).
- `d-access`, `d2-revoke-id`, `d3-actions`.
- `h-fold` (Chromium and Firefox), `h-history`, `f-blocked-conflict` (Firefox, dark).
- `i-sweep`: axe, overflow and running animations with reduced motion; 1440 dark, 390 dark and light, 720@2x; Chromium and Firefox.
- `j-sticky`, `k-contrast`/`k2` (the 390 colour-contrast incomplete is the scrollable nav, not a defect) and `l-leftovers`.

**Unverified:**
- **No screen reader.** I did not run VoiceOver or NVDA. The real AX tree comes from Chromium CDP only; the Firefox and WebKit table semantics come from Playwright's own computation, not the engine's tree. WebKit has historically dropped table roles under `display: block/grid/contents`, so Safari with VoiceOver should be checked by hand.
- **Zoom and resize.** "200% zoom" was emulated as a 720 viewport at 2x DPR. Real Ctrl+ zoom and real device rotation were not driven.
- **Families that open on Error.** No Error-bearing head is reachable in the demo.
- **Live backend.** I did not run it: odd-path handling (Issue 5) and the real lost-update behaviour on the server (it will accept, since the head matches) are unverified.
- Branded Safari and Firefox, and real touch.
