## Scores

| dimension | score | justification |
|---|---|---|
| typography | 8 | Serif headings and mono paths still hold. Two weak spots. Check facts are flattened dotted names (`timingMetadata.RepetitionTime.origin`), which break mid-word at 1440px ("…origi / n"). Fact values carry no units (`temporalSpacingSeconds 2`). |
| color | 8 | The palette is calm. The new NOT EVALUATED tag (grey) is clearly different from RESOLVED (green). Content-check chips are still amber whatever the severity. |
| layout | 8 | Assessment is compact (one card per rule family). The preview's two columns read well. Two small problems: the "Revoked <date>" pill wraps to two lines in a cramped Access column, and the "2 findings" pill wraps at 390px. |
| hierarchy | 8 | Preview order is good: affected files, diff, content, candidate, then findings. But the outcome the user cares about most ("No longer reported · RESOLVED") is the last block, at the bottom right. In the inspector, the TR finding sits about 900px above the facts that explain it. |
| components | 7 | The new-sidecar editor has a state bug that silently drops a valid edit (Issue 1). Query's operator select is truncated to "Column at". Input chips now print the file names, which fixes v3 #3. |
| function | 7 | All five tasks were completed. But the natural route for task 2, editing the suggested path to a subject-level one, silently resets the form and discards the prepared candidate, with no message. Validation messages are otherwise clear: invalid JSON, the "≥30" value and a non-UUID recipient are all explained. |
| themes | 8 | Dark mode is coherent. The primary button is still the brightest element. |
| mobile | 8 | 390px has no overflow (scrollWidth 390). The tree folds to one line and the inspector is 2174px for sub-04. |
| identity | 8 | It keeps the archival instrument character. The DEMO DATA badge is honest. |
| fidelity | 7 | Better: v3 #1 is fixed with NOT EVALUATED, the cause lines are now quantity diffs, the vocabulary matches the backend, and the fieldmap direction is corrected. Weaker: the demo ignores subject-level sidecars. It previews a valid BIDS `sub-04/sub-04_task-rest_bold.json` as "0 files affected · Admissible", where BIDS inheritance would apply it. A live origin-only change is listed under "Effective metadata changes" as `1.5 s → 1.5 s`. |

## Previous round

My v3 issues, by number:

| # | v3 issue | v4d status |
|---|---|---|
| 1 | Blocked candidate shows "RESOLVED" | **Fixed.** TR 0 now shows `EventsOutsideWindow · NOT EVALUATED`, with "Changed: comparisonEndSeconds 405 → absent …". `InconsistentMetadata × 3 · Blocks acceptance · NEW` is listed on the affected images. |
| 2 | Cause line is mechanistic | **Largely fixed.** The resolved card reads: "Changed: timingMetadata.RepetitionTime.origin task-rest_bold.json → sub-04/…json; timingMetadata.RepetitionTime.value 1.5 → 2." It still does not say "header 2 s, unchanged", which is the reason it now agrees. |
| 3 | Input file names hover-only | **Fixed.** Chips print `metadata-0 task-rest_bold.json 423d94cb90c3`. |
| 4 | Vocabulary diverges from backend | **Fixed** in the demo: 0-based roles, `temporalSpacingSeconds`, `timingMetadata.*`, `comparisonEndSeconds`. **The predicted cost is confirmed live:** the sub-02 header check shows 21 facts in alphabetical order, including `sformRows [[0,0,0,0],…]` and `quaternion`. The two facts that matter, `temporalSpacingSeconds` and `timingMetadata.RepetitionTime.value`, are not featured. |
| 5 | Revoke time is the client clock | **Not addressed.** It still reads "Revoked Oct 12, 2026, 11:23 PM EDT" in the same style as a server time. |
| 6 | Conflict message ambiguous | Not re-tested this round. |
| 7 | Header value not next to the RepetitionTime row | **Not addressed.** sub-04 shows `RepetitionTime 1.5 s · Inherited from task-rest_bold.json` with no "header says 2 s" next to it. |
| 8 | Chips always amber | Not addressed. |
| 9 | "1 findings" screen-reader text | Not checked. |
| 10 | Authored `2.0` shown as `2` | Not addressed. The placeholder suggests `2.0`, and the preview shows `2`. |

## Claims

All claims were driven in Chromium against `out/v4d/index.html?demo`, served locally on :8743 and since stopped. Scripts are `p1–p16.cjs` and screenshots are `NN-*.png` in `critiques/v4d-user-work/`. No console or page errors occurred in any run.

**Task 1: Is the release admissible, and what needs attention?**
- The header says `Admitted`, with Profile and BIDS version, and the Assessment tab badge shows `4`. Nothing on the landing Files tab says *what* the 4 are; I had to open the tab.
- The Assessment tab shows: 0 errors, 4 warnings, 28 checks, `reference-admission-v7`. The cards are:
  - EventsOutsideWindow ×2 (sub-03 events)
  - MriRepetitionTimeMismatch (sub-04 rest)
  - MriTransformUnavailable (sub-04 rest)
- The rule-family accordions summarise "1 with findings" or "All consistent".
- Verdict: clear in about 30 s. The "Recorded warning" label makes plain that nothing blocks.
- Weakness: the TR finding text ("authored TR differs from header spacing converted to seconds") gives no numbers. Header 2 s against authored 1.5 s is only visible after expanding "16 recorded facts" on the file.

**Task 2: TR mismatch, fixed by adding a sidecar.**
- In the demo, sub-02 n-back is already consistent: its run sidecar of 2 s overrides 1.5 s, and the header check reads Consistent. The live mismatch case is sub-02 (`live-evidence/shots-v4d/chromium/fail-add-sidecar-accept.png`), and the demo's equivalent is sub-04 rest. I fixed that one.
- The sub-04 rest inspector has an "Add sidecar" button. It opens "Add sub-04_task-rest_bold.json", with the path prefilled as `sub-04/func/sub-04_task-rest_bold.json`.
- **Run-level path, accepted.**
  - The preview shows `RepetitionTime 1.5 s from task-rest_bold.json → 2 s from sub-04/func/…json`, the diff `+ { "RepetitionTime": 2 }` and "New file 24 B".
  - The candidate is Admissible: "0 new, 3 unchanged, 1 no longer reported", with MriRepetitionTimeMismatch shown as RESOLVED and its quantity cause line.
  - I accepted. The receipt shows three "Matches preview" chips and "Open new release" (`09-preview.png`, `10-receipt.png`).
  - I could tell exactly what accepting would change. This is a good decision screen.
- **Subject-level path (what the task asked for):** I edited the path to `sub-04/sub-04_task-rest_bold.json` and submitted. The form came back with the path reset to the func default, the content empty, and no message (`p7`, `07b-preview-subject.png`). See Issue 1.
- Through "Propose correction" (no `?path`), the same subject-level sidecar does prepare. The preview reads "0 files affected · No effective metadata changes · Admissible", and the mismatch is UNCHANGED (`08-preview-subject.png`).
  - The preview is honest about what the demo computed, so I would not accept it.
  - But per BIDS inheritance this sidecar should apply. The demo's `layers()` only considers root task-level sidecars and exact-stem sidecars (`demo.js:76–81`). See Issue 2.
- **Live run (builder evidence):** "Add sidecar" for sub-04 n-back previewed `1.5 s → 1.5 s` under "Effective metadata changes · 1 file affected". This is an origin-only change presented as a value change (`live-evidence/shots-v4d/chromium/preview.png`).

**Task 3: Which fieldmaps correct which runs.**
- **Relationships tab** (`11-relationships.png`):
  - "Fieldmaps by run 8 · Each run and the fieldmaps whose IntendedFor names it". Each row is run → `sub-0X/fmap/sub-0X_dir-AP_epi.nii.gz`, "Declared by …AP_epi.json · IntendedFor", and the arrow's accessible label is "corrected by".
  - The direction is correct and the provenance is explicit.
- **Demo PA fieldmap:** its inspector carries `B0FieldIdentifier pepolar02` but shows no Linked acquisitions section at all. Nothing says that no run selects it.
- **Live B0 view** (`live/shots-b0/chromium/relationships.png`): "B0 field sources 1" shows `pepolar02 · sub-02 rest → AP + PA`, declared by both B0FieldIdentifier sidecars and the run's B0FieldSource. This answers the question correctly.
- However, the answer is split across two sections ("Fieldmaps by run" and "B0 field sources"). The section order also differs between demo and live: in demo, Fieldmaps comes first; in live, DWI gradients comes first.

**Task 4: Freeze participants aged ≥ 30.**
- The Query page is prefilled with `age` and `suffix bold`.
- Typing "≥30" gives "“≥30” is not a plain decimal number, such as 25 or 1.5." That message is good.
- With `30` and the suffix condition removed, the result is "12 matching files · Frozen · Selection 55178d9e59f5 · 2 participants · Frozen from: participant age ≥ 30": sub-02 (31) and sub-04 (38). This is correct per participants.tsv (`14-selection.png`).
- The list is images only: no sidecars, events or bval/bvec. Nothing explains that. Participant ages are not shown, and there is no export or copy of the list.

**Task 5: Release-scoped read grant, then revoke.**
- The scope defaults to "Release 9d4b2e61". Propose and Accept are disabled, with "Release grants permit reading only".
- An email address is rejected: "The recipient must be a principal UUID."
- With a UUID, grant aa48dbaf shows Recipient 7c1e2b44, Scope Release 9d4b2e61, Actions **Read metadata only**. "Read content" is unchecked by default.
- Revoke → Confirm revoke / Cancel → "Revoked Oct 12, 2026, 11:23 PM EDT" (`17-granted.png`, `18-revoked.png`).

**Mobile (390px), sub-04 inspector:** no horizontal overflow and a 2174px page. The tree folds to "Files · 60 / path · Browse" (`19-mobile-sub04.png`).

## Issues

Severity: B = blocker, M = major, m = minor.

1. **M (function, confirmed): editing the prefilled new-sidecar path loses the edit.**
   - Steps: from a file's "Add sidecar", change `sub-04/func/sub-04_task-rest_bold.json` to the subject-level `sub-04/sub-04_task-rest_bold.json`, fill in content and a reason, then click Prepare preview.
   - Result: the path reverts, the content is cleared and no error appears. Only the reason survives.
   - Cause, from reading `app.js:680–683` and `728–733`: submit sets `c.path` to the new path. `correctionView` then re-renders with `want` taken from the URL (`?path=…func…`). `c.path !== want` resets the correction state, so any candidate just prepared is thrown away. The same likely affects choosing a different sidecar in the dropdown after arriving via `?path`.
   - Impact: this is exactly the "subject-level sidecar" fix a lab manager would try. Silent data loss in the correction editor costs trust.
2. **M (fidelity, demo): subject-level sidecars are not inherited in the demo.**
   - The demo's `layers()` ignores `sub-XX/` level sidecars. A BIDS-valid subject-level override previews as "0 files affected", "Admissible", with Accept enabled.
   - Meanwhile the editor's hint promises "BIDS inheritance applies it to the files it matches".
   - It is unverified whether the backend applies it. If the backend does, the demo misleads training and screenshots. If it does not, the hint is wrong.
   - Separately, a candidate whose new sidecar applies to no files deserves a warning such as "This sidecar applies to no files." It should not look like a clean Admissible.
3. **m–M (decision support): the mismatch finding does not show its numbers.**
   - "RepetitionTime: authored TR differs from header spacing converted to seconds" in the inspector and assessment. The values (header 2 s, authored 1.5 s from task-rest_bold.json) sit in an unordered grid of 16–21 facts, with names truncated across lines.
   - Show `header 2 s · authored 1.5 s (task-rest_bold.json)` on the finding, and next to the RepetitionTime row (v3 #7).
   - Feature 3–4 facts per rule and fold the rest. Live, `sformRows` and `quaternion` crowd them out.
4. **m (fidelity, live): origin-only changes are listed as value changes.** `RepetitionTime 1.5 s → 1.5 s` appears under "Effective metadata changes · 1 file affected". Label it "Origin only · value unchanged", or separate the two.
5. **m (hierarchy): the payoff is last in the preview.** "No longer reported · RESOLVED" is the bottom block of the right column. Echo it in the summary line, for example "Resolves: MriRepetitionTimeMismatch (sub-04 rest)", next to Accept.
6. **m (task 3): fieldmap answer is split, and unused fieldmaps are silent.**
   - IntendedFor and B0FieldSource links live in two separate sections.
   - A fieldmap no run selects (demo PA) shows no Linked acquisitions section at all.
   - Merge the two into one "Fieldmaps by run" table with a "declared via" column. Give a fieldmap with no runs a "Not selected by any run" line.
7. **m (adoption): grants require a principal UUID.** No hint says where to find a colleague's principal, and there is no lookup. "Read content" is off by default, so "give Jane read access" yields metadata-only unless the user notices. Add one line, "Ask your colleague for their principal id (shown next to their avatar)", and make the actions summary prominent before Create.
8. **m (query): output gaps.**
   - "12 matching files" does not say that only images are included (no sidecars or companions).
   - Participant attributes are not shown.
   - There is no export (TSV or copy paths).
   - The operator select is visibly truncated to "Column at" at 1440px (`13-query.png`).
9. **m (carried over):** the revoke time is the client clock (v3 #5); chips are always amber (v3 #8); `2.0` shows as `2` (v3 #10).
10. **m (layout):** the "Revoked …" pill wraps to two lines in the Access table. The `2 findings` pill wraps at 390px.

## Regressions

- **Silent loss of a correction draft (Issue 1)** is new with the v4 "New sidecar…" feature. In v3 there was no free-text path, so there was no way to hit it.
- **The demo B0 set {AP, PA} is gone.** The builder justified this as removing a fabrication, and live now shows "B0 field sources" correctly. But in the demo the PA fieldmap has no visible role at all, so a demo user loses the answer v3 gave.
- I found no regressions to task outcomes on the paths the builder drives.

## Proposals

1. Fix the correction state key. Key the correction on base release plus the intent in the URL. When submit changes the path, update the hash (`?path=<new>`) before re-rendering. Add a probe: edit the new path, submit, and assert the preview is shown.
2. Make the demo's inheritance walk ancestor directories (dataset → subject → session → datatype) to match BIDS, or say in the hint which levels are honoured. Warn on a candidate whose new sidecar affects 0 files.
3. Put the numbers on TR findings, and add a "Header: 2 s · disagrees/agrees" sub-line under RepetitionTime. Feature the key facts per rule.
4. Add a "Resolves / Introduces" summary next to Accept.
5. Merge the fieldmap views, and say "not selected by any run" for unused fieldmaps.
6. Access: add guidance for finding a principal id; restate the actions in the Create button, for example "Grant read metadata + content".
7. Query: add an "images only" note, participant columns and Copy/Export TSV; widen the operator select.

**Adoption verdict (data manager):**
- **Adopt for inspection, assessment triage, fieldmap checks, selections and grants.** Tasks 1, 3, 4 and 5 completed first time with clear feedback.
- **Adopt the correction flow with one caveat.** The preview gives real decision support: what changes, which findings resolve and why, and a receipt that proves identity. But Issue 1 means the subject-level route silently fails from the file page. A user who does not notice will think Sanctity rejected their fix.
- Fix Issue 1, and settle the subject-level semantics (Issue 2), before handing this to lab staff.
