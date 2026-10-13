## Scores

| dimension | score | justification |
|---|---|---|
| typography | 8 | Unchanged. Units now read with a space in the a11y tree ("0.03 s", "52°"). Facts still print without units. Grouped messages print a literal "row … column …". |
| color | 8 | Error, warning, resolved and not-evaluated tags are distinct in light and dark. The red Error chip is now reachable (TR −1 gives InconsistentMetadata × 3, "Blocks acceptance", rgb 255,146,131 in dark). |
| layout | 8 | No page overflow at 390, at 720 (200% zoom) or at 1440, in light or dark. The decision panel is static at 200% zoom (1126px tall in a 500px viewport), which is the correct choice. |
| hierarchy | 8 | The assessment page is short again: 1000px at 1440 and 1593px at 390. At 390 the findings start about 170px below Accept (783 vs 956), which is better than v3b. |
| components | 8 | The rule-family disclosures and the "N recorded facts" disclosures work with keyboard (Enter and Space) and keep focus. The table Revoke buttons are still all named "Revoke". |
| function | 7 | v3b issues 2, 4 and 6 are fixed and verified. Issues 1, 5 and 7 are fixed in code. The new headline flow has a confirmed data-loss defect in all three engines: if the user edits the prefilled path of a new sidecar, the prepared candidate and the content are discarded, while "Candidate prepared" is announced. |
| themes | 8 | Dark mode: axe reports 0 violations on the inspector, the blocked preview and 390. The only serious incomplete is the known `pre[aria-label]`. |
| mobile | 8 | The tree fold works at 390 and 720. The summary is reachable by keyboard and opens with Enter, and choosing a file refolds and moves focus to the inspector. The fold is computed at render time only, so widening the window keeps it folded. |
| identity | 8 | Unchanged. The DEMO DATA badge is honest. |
| fidelity | 8 | Admission findings no longer get a false outcome claim. TR −1 marks EventsOutsideWindow "Not evaluated", which is correct. Row grouping now hides which rows are outside the window: "row … column …" with no row list anywhere. |

## Previous round

| v3b issue | status in v4d | evidence |
|---|---|---|
| 1. An admission finding fixed by an edit is labelled "Not evaluated" | **Fixed in code.** `outcome()` returns no tag when no content check produced the finding (app.js 832–833). Not exercised end to end: the demo's FieldmapMissingTarget is now an Error, so it can never be on an accepted head. | source |
| 2. A revoke is announced to the next principal | **Fixed.** `announce` and `focusKey` sit inside `owner === state.principal` (app.js 1114–1118). | source |
| 3. NEW findings get no moved facts | **Fixed.** The gate probe passes. My blocked preview shows "Re-checked because task-nback_bold.json is an input of …" on the NEW InconsistentMetadata. | `f-blocked-conflict.*.out` |
| 4. Access table stale after a round trip | **Fixed.** With 1.5 s latency: Create → Query → Back gives 1 row before the response and 2 after it. A grant that lands while the user is on Query shows 3 rows after Back. A revoke round trip shows "Revoked …". | `d-access.chromium.out` |
| 5. Absent facts not treated as unevaluated | **Fixed in code** (`!(k in y)`, app.js 838). | source |
| 6. Double Enter creates two grants | **Fixed.** Double Enter sends 1 POST. | `d-access.chromium.out` |
| 7. Unknown scope shown as "Dataset" | **Fixed** ("Unknown", app.js 1150). | source |
| 8. Accessibility leftovers | **Still open.** `pre.linediff[aria-label]` is still axe serious incomplete `aria-prohibited-attr` in Chromium and Firefox, light and dark, 390 and 1440. `span.count[aria-label]` remains: the tab is named "Assessment 4 items", not "4 findings". "Recorded by this browser" is still in `title` only. | `a-*.out`, `e-mobile.chromium.out` |

## Claims

| claim (changes-v4d) | verdict |
|---|---|
| New-sidecar option with path validation | **Partly true.** These are rejected client-side, with `aria-invalid` and focus on the path field in Chromium and Firefox: `../x.json`, `/abs`, `.txt`, `//`, `\`, `./`, `A.JSON`, an existing file, empty and whitespace. A bare `.json` passes the client check and comes back as a generic invalid_request, with focus on Reason (Issue 4). Editing the prefilled path breaks the flow (Issue 1). |
| "Add sidecar" on images without one | **True.** By keyboard: filter, ArrowDown to the BOLD file, Enter, 11 Tabs, then Enter on "Add sidecar". The path is prefilled. The receipt shows Matches preview × 3, and the new head shows "This file's sidecar 1.8 s" with the overridden 1.5 s step. Checked in Chromium and Firefox. |
| Findings group by message shape, with per-file counts; more than 4 files collapse | **True, but information is lost** (Issue 2). |
| Checks: one disclosure per rule family; facts behind "N recorded facts" | **True.** The page is 1000px tall at 1440. All families start collapsed, including flagged ones, although the code comment says they open (Issue 5). |
| Access refreshes after a round trip, keeping focus | **True** (see the table above). |
| Mobile tree folds to a one-line disclosure | **True** at 390 and 720 in Chromium and Firefox. 17 Tab stops (18 in Firefox) precede the summary. The fold does not respond to resize (Issue 6). |
| Units read with a space in the a11y tree | **True in my run.** But the shipped `live-evidence/aria-*.txt` still shows `0.03s`, so that evidence predates or does not reflect the build. |
| Live: a real correction was accepted, and the new head drops the TR mismatch | **Not supported by the v4d live evidence.** `shots-v4d/*/log.txt` shows the add-sidecar step prepared a preview only (ACCEPT unset) with "0 new, 24 unchanged, 0 no longer reported; mismatch tag: absent". `fail-add-sidecar-accept.png` is a byte-identical copy of `inspect-bold.png`. The claim may come from an earlier run. |
| Gate exit 0 | **Confirmed** in `logs/gate-v4d.log`. My probes saw 0 page errors and 0 console errors in every engine and state. |

## Issues

Severity: M = major, m = minor.

1. **M (function, data loss, misleading announcement): editing the prefilled new-sidecar path discards the prepared candidate.**
   - Repro (`b1-detail.{chromium,firefox,webkit}.out`, `shots/b1-reset-*.png`):
     - Open `sub-03/func/sub-03_task-nback_run-1_bold.nii.gz` and choose "Add sidecar". The route carries `?path=…run-1_bold.json`.
     - Change the path to `sub-03/sub-03_bold.json`. Type content and a reason, then press Enter.
   - Result:
     - One POST /corrections is sent and "Candidate prepared. Review the preview." is announced.
     - The page shows step 1 Edit again: the path reverts to the URL's path, the content is empty, there is no error, and focus is on `<body>`.
   - Choosing "New sidecar…" from the select without a `?path` works, because the route carries no path.
   - Cause: `correctionView` resets the state when `want && c.path !== want` (app.js 681). Submit sets `c.path` to the typed path, but the hash still holds the prefilled one.
   - Remedy: in new mode, `history.replaceState` the hash to `?path=<typed path>` before `render()`. Alternatively, record the route path the draft was created for and compare against that.
2. **m (fidelity/function): grouped row findings drop every row number.**
   - EventsOutsideWindow × 2 reads "…review timing alignment row … column …", with "sub-03/…events.tsv × 2".
   - Neither the assessment list nor the events.tsv inspector says which rows (50 and 51) are affected. The only locator the backend gives is elided, and the count "× 2" appears twice without a noun.
   - Remedy: keep the shape as the heading and list the elided values ("rows 50, 51"), or put the original messages in a `<details>`. Read the count as "2 rows".
3. **m (a11y): error association gaps.**
   - "Choose at least one action." leaves focus on the submit button. It is not tied to the Actions fieldset and sets no `aria-invalid`.
   - Revoke-by-id sets `aria-invalid` but has no `aria-describedby` pointing at its error (`d-access.chromium.out`).
4. **m (function): a path of `.json` (empty basename) passes client validation.**
   - The server answers with a generic "refused the request as invalid…".
   - Focus stays on Reason and the path field is not marked invalid.
   - Remedy: require a non-empty basename. In new mode, map invalid_request to the path field.
5. **m (hierarchy/a11y): flagged rule families are collapsed.** The comment at app.js 628 says families with findings open, but no `open` is set. A keyboard user must open "bids-events-content-v1 · 1 with findings" to see which check flagged. Remedy: open flagged families, or fix the comment.
6. **m (mobile): the fold is evaluated once per render.** After widening 390 → 1440 without navigating, the desktop still shows the folded tree (`shots/e2-widened-*.png`). Narrowing does not fold either. Remedy: CSS-driven fold, or re-render on a `matchMedia` change.
7. **m (function): "Correct the current head" after a conflict carries the reason but drops the edited content.** The user must re-type the edit even when the sidecar is unchanged on the new head (`f-blocked-conflict.chromium.out`). Remedy: carry the draft when the file's content is identical on the new head.
8. **m (a11y), carried over:**
   - `pre.linediff[aria-label]` triggers aria-prohibited-attr.
   - `span.count[aria-label]` produces tab names like "Files 60 items".
   - The Revoke buttons have no grant context; suggest `aria-label="Revoke grant def64775"`.
   - Revoked provenance is in `title` only.
   - Back navigation focuses `<main>`, not the control that opened the view (for example, the finding path button). This is acceptable but could be better.

## Regressions

- I found no lost functionality in the flows I re-ran:
  - accept, receipt identity check, conflict link, blocked preview with disabled Accept and flagged diff line
  - Back to edit, which keeps the text and focuses `#c-text`
  - revoke confirm, Escape (focus returns to Revoke) and revoke-by-id 404
  - tablist arrow keys, tree arrow keys, filter
  - history: tab switches replace the entry; file opens push one. Back and Forward restore the tab and path.
- Issue 1 is new in v4d because the new-sidecar path field is new.
- Firefox adds one extra Tab stop: a focusable scroll container in the release strip. This is platform behaviour, not a defect.

## Proposals

1. Fix Issue 1 first. It sits on the round's headline flow and produces a false success announcement.
2. Keep row and column values in grouped findings (Issue 2).
3. Open rule families that have findings, and make the tree fold responsive to resize.
4. Finish the accessibility leftovers in one pass: diff `role="region"` or a heading label, count spans as visually-hidden text, contextual Revoke names, and `describedby` on the actions and revoke-by-id errors.

**What I ran** (probes and outputs in `critiques/v4d-function-work/`, static server on 127.0.0.1, demo transport, real Playwright keyboard and mouse input):
- `a-keyboard-sidecar` (Chromium and Firefox): Tab order and focus outline at each stop, filter and arrow tree, Add sidecar, 11 bad paths, invalid JSON, blank reason, preview, accept, receipt, Back/Forward.
- `b-newpath-edit` and `b1-detail` (3 engines): path edit, select, drafts, reload.
- `c-assessment`: tablist keys, grouped findings, disclosures, aria snapshot, inspector of events/bold/rest.
- `d-access` and `d2-revoke-id`: validation, double Enter, latency round trips for grant and revoke, Escape, unknown id.
- `e-mobile` and `e2-fold-kbd` (390 light and dark, 720 at 200% zoom, reduced motion: 0 running transitions or animations; Chromium and Firefox).
- `f-blocked-conflict` (dark, Chromium and Firefox).
- `g-contrast`; `h-history`.
- axe-core on every state listed: 0 violations.

**Unverified:**
- **No screen reader.** I did not run VoiceOver or NVDA; I have only Playwright aria snapshots and axe.
- **WebKit keyboard results are inconclusive.** WebKit's default Tab skips buttons and links (macOS convention).
- **Native select keyboard behaviour on macOS Chromium.** ArrowDown did not change the value, so per-option re-rendering is untested.
- **Live backend.** I did not run it; I reviewed its logs only.
- Real touch, branded Safari and branded Firefox.
- A one-off axe color-contrast "incomplete" at 720px that did not reproduce.
