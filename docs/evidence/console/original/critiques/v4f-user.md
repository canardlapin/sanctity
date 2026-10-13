## Scores

| dimension | score | justification |
|---|---|---|
| typography | 8 | Serif headings and mono paths still hold. Flattened fact names still break mid-word at 1440px: `timingMetadata.RepetitionTime.origi / n` on the sub-04 rest inspector (`05-sub04-rest.png`). On the TR finding, values now carry units (`1.5 s`, `2 s`). |
| color | 8 | Calm and consistent. A resolved finding still carries an amber "Recorded warning" chip beside the green RESOLVED tag, which sends a mixed signal (`07-run-preview.png`). The 0-files warning is correctly amber. |
| layout | 8 | Preview, assessment and access are tidy. The "Revoked Oct 13, 2026, 05:39 AM EDT" pill still wraps to two lines and becomes a large grey blob in the 1440px Access table (`24-d-revoked.png`). At 390px the grant table becomes labelled blocks, cleanly, with no overflow. |
| hierarchy | 8 | At 1440px "No longer reported" now sits directly under Accept, which fixes v4d #5. At 390px the Candidate card and Accept come first: the view auto-scrolls to Accept, and the metadata diff and sidecar diff sit below all findings (`27-m-preview.png`). |
| components | 8 | The new-sidecar editor now holds an edited path, so v4d #1 is fixed. Validation is good: a nameless `.json` path, invalid JSON, `≥30`, a non-UUID recipient and "no action" each get a specific message and focus the right field. The Query operator select is still cut off as "Column at" at 1440px. |
| function | 9 | All five tasks were completed first time on both paths, including the subject-level sidecar, at 1440px and at 390px. After a grant, focus moves to that grant's Revoke and a live region announces "Grant … created." "Correct the current head" keeps the edited content and adds a review note. |
| themes | 8 | Not re-examined in depth. A dark-mode capture of the inspector exists (`28-dark-sub04.png`), and nothing new looked broken. |
| mobile | 8 | 390px has no horizontal overflow on the inspector, preview, selection or access pages (scrollWidth 390). Selection and grant rows become labelled blocks with Revoke full width. The cost is length: each selection row is about 225px tall and repeats "RELEASE 9d4b2e61". In the preview, Accept comes before the diff (see hierarchy). |
| identity | 8 | Still an archival instrument, and the DEMO DATA badge is honest. |
| fidelity | 9 | The demo now applies BIDS inheritance: the subject-level `sub-04/sub-04_task-rest_bold.json` previews `1.5 s → 2 s` and resolves the mismatch. After acceptance the inspector shows `task-rest_bold.json 1.5 s OVERRIDDEN / sub-04/sub-04_task-rest_bold.json 2 s EFFECTIVE`. A same-value sidecar is tagged "SAME VALUE, NEW SOURCE", and a sidecar that matches nothing gets a warning. Not verified live: subject-level inheritance on the real backend, and the v4f TR-value display (the newest live capture, v4e, predates it). |

## Previous round

My v4d issues, by number:

| # | v4d issue | v4f status |
|---|---|---|
| 1 | Edited new-sidecar path silently lost | **Fixed.** I changed `sub-04/func/…` to `sub-04/sub-04_task-rest_bold.json` from "Add sidecar". The preview kept the path, the URL updated to `?path=sub-04%2Fsub-04_task-rest_bold.json` and the content was kept (`p5-subject.txt`, `07-subject-preview.png`). |
| 2 | Subject-level sidecars not inherited in the demo; no 0-files warning | **Fixed in the demo.** The subject-level sidecar affects 1 file and resolves the mismatch. `sub-04/sub-04_task-memory_bold.json` gives "0 files affected" plus an amber "The new sidecar changes no file's effective metadata. BIDS inheritance applies it only to files in its directory or below whose entities and suffix it matches." (`11-nomatch.png`). Backend behaviour is still unverified by me. |
| 3 | TR finding has no numbers | **Largely fixed.** The finding now shows `Authored RepetitionTime 1.5 s · Header volume spacing 2 s` in the Assessment, the inspector and the preview's UNCHANGED cards. Still missing: which file the authored value came from (you scan down to the RepetitionTime row), and the "16 recorded facts" grid is still unordered. |
| 4 | Origin-only change shown as a value change | **Fixed in the demo.** The change shows as `1.5 s → 1.5 s` with a "SAME VALUE, NEW SOURCE" tag (`p7b.txt`, `11-samevalue.png`). The header still counts it as "1 file affected" under "Effective metadata changes". That is acceptable. |
| 5 | Payoff is last in the preview | **Fixed at 1440px.** "No longer reported" sits directly below Accept. At 390px see Issue 2. |
| 6 | Fieldmap answer split; unused fieldmap silent | **Not addressed.** The demo PA fieldmap (`B0FieldIdentifier pepolar02`) still shows no Linked acquisitions and no "not selected by any run" (`17-sub-02_dir-PA_epi.nii.gz.png`). |
| 7 | Principal UUID friction; "Read content" off by default | **Partly.** "Choose at least one action" is now tied to the Actions group and focuses it. There is still no hint on where to find a principal id. "Read content" is still unchecked by default, and the Create button does not restate what will be granted. |
| 8 | Query output gaps / truncated select | **Not addressed.** There is still no "images only" note, no participant attributes and no export, and "Column at" is still cut off at 1440px (`18-d-query.png`). |
| 9 | Carried: client-clock revoke time, amber chips, `2.0` → `2` | **Not addressed.** The diff faithfully shows `2.0`, but the value column shows `2 s`. That is arguably right for an effective value. |
| 10 | Revoked pill wraps; "2 findings" pill wraps at 390px | **Partly.** The 1440px Revoked pill still wraps. At 390px it is full width and fine. |

## Claims

All runs were driven in Chromium (Playwright) against `out/v4f/index.html?demo`, served from my work dir on 127.0.0.1:8761 and since stopped. Scripts are `p1–p15.cjs` (plus `lib.cjs`), with logs `p*.txt` and screenshots `NN-*.png` in `critiques/v4f-user-work/`. There were no console or page errors in any run.

**Task 1: Is the release admissible, and what needs attention?** (`02-dataset.png`, `04-assessment.png`)
- The header reads "Admitted · Profile native-reference-v7 · BIDS 1.10.0", and the Assessment tab badge reads 4.
- Assessment shows 0 errors, 4 warnings and 28 content checks. The finding cards are:
  - EventsOutsideWindow "2 occurrences … rows 50, 51 column 1", sub-03 events.
  - MriRepetitionTimeMismatch with the values 1.5 s and 2 s.
  - MriTransformUnavailable.
- Verdict in about 20 s: admissible, with three things to look at, and the TR one is self-explanatory now.
- The landing Files tab still gives no hint of *where* findings are until you expand a subject. Files carry a "2" badge, but collapsed subject folders do not.
- "column 1" does not say whether it is 0- or 1-based, or which column (onset? duration?).

**Task 2: TR mismatch, fixed by adding a sidecar.**
- Run-level, from sub-04 rest → Add sidecar (path prefilled `sub-04/func/sub-04_task-rest_bold.json`) (`07-run-preview.png`):
  - The preview reads "Effective metadata changes · 1 file affected · RepetitionTime 1.5 s from task-rest_bold.json → 2 s from sub-04/func/…".
  - The diff `+ { "RepetitionTime": 2.0 }` is shown, along with "New file 26 B".
  - The candidate is Admissible: "0 new, 3 unchanged, 1 no longer reported".
  - "NO LONGER REPORTED: MriRepetitionTimeMismatch RESOLVED" sits directly under Accept, with the cause line.
  - I knew exactly what accepting would change.
- Subject-level, the same route with the path edited to `sub-04/sub-04_task-rest_bold.json` (`p5-subject.txt`):
  - It gives the same decision screen, with the after-source `sub-04/sub-04_task-rest_bold.json`.
  - I accepted (`p6.txt`). The receipt shows a new release 8a864adb and three "Matches preview" chips.
  - Open new release shows 61 files, Assessment 3, and the sidecar listed in the tree at subject level.
  - The RepetitionTime row is `2 s · Inherited from sub-04/sub-04_task-rest_bold.json`, with the overridden 1.5 s struck through (`09-new-release.png`). This is the inheritance explanation I want.
- Edge cases:
  - Nameless `sub-04/func/.json` is refused with "Name the sidecar after the files it applies to…", and focus moves to the path field (`11-nameless.png`). The example names sub-02 while I am working on sub-04.
  - Invalid JSON gives a precise line/column message.
  - A non-matching name gives the 0-files warning.
  - With the same value, the change is tagged "SAME VALUE, NEW SOURCE".
- Stale base (`p8c.txt`, `14-stale-correct.png`, `15-correct-head.png`):
  - After a different correction became head, the old release's correction page shows "Release 9d4b2e61 is not the current dataset head…" with a "Correct the current head" link, and Prepare is disabled.
  - The link carried my edited content to the head, with the note "This content was written against release 9d4b2e61 … review the preview's line changes".
  - The old release page swaps "Propose correction" for "Go to current head".
- Accept has no confirmation step. That is acceptable, because the preview *is* the confirmation and the action is additive.

**Task 3: Which fieldmaps correct which runs?** (`16-relationships.png`)
- "Fieldmaps by run 8" lists run → AP fieldmap, "Declared by …AP_epi.json · IntendedFor". The direction and provenance are clear.
- The PA fieldmap inspector shows `B0FieldIdentifier pepolar02` and no links at all, with nothing saying it is unused. The AP inspector shows the heading "Fieldmaps by run" twice, once per run.
- Live B0 view: unchanged from v4d (`live/shots-b0/chromium/relationships.png`), not re-run.

**Task 4: Freeze a selection of participants aged ≥ 30.**
- At 1440px, "≥30" is rejected with "“≥30” is not a plain decimal number, such as 25 or 1.5."
- `30` with no file condition gives "12 matching files · Frozen · Selection 55178d9e59f5 · 2 participants · Frozen from: participant age ≥ 30" (sub-02, sub-04). That is correct.
- At 390px with `suffix bold` kept: "4 matching files", shown as labelled blocks, with no overflow (`19-m-selection-bold.png`).
- Still: no ages shown, no export, and nothing says "images only".

**Task 5: Grant release read access, then revoke.**
- At 1440px:
  - An email recipient is rejected with "must be a principal UUID", and focus goes to the recipient field.
  - With no actions ticked: "Choose at least one action.", and focus goes to Read metadata (`21b-d-access-noactions.png`).
  - The grant appears in a table, Revoke → Confirm revoke/Cancel, then "Revoked Oct 13, 2026, 05:39 AM EDT" (`24-d-revoked.png`).
  - The Revoke button's accessible name is "Revoke grant 433a2359".
- At 390px:
  - After Create, the view scrolls so that the new grant block and a full-width Revoke are on screen, focus is on Revoke, and the live region says "Grant d717fdf5 created." (`25-m-after-grant-viewport.png`).
  - Confirm revoke and Cancel sit side by side (`23-m-confirm.png`).

## Issues

Severity: B = blocker, M = major, m = minor.

1. **m (decision support, confirmed): a "changes nothing" candidate still looks like a clean go.**
   - Screen: correction preview, `11-nomatch.png`.
   - The amber warning sits in the left column. The right column still shows a green "Admissible" badge, a primary "Accept as new release" button and "0 new, 4 unchanged, 0 no longer reported".
   - A user scanning the right column could accept a no-op release.
   - Fix: echo the warning by the Accept button, for example "This adds a file but changes no metadata", or make Back to edit the primary action.
2. **m (mobile hierarchy, confirmed): at 390px Accept comes before what is being accepted.**
   - Screen: correction preview at 390px, `27-m-preview.png`.
   - The order is Candidate → Accept → findings → Effective metadata changes → Sidecar change → Content changes, and the page auto-scrolls to Accept (scrollY 544).
   - On a phone you are invited to accept before seeing the diff.
   - Fix: put the metadata change (RepetitionTime 1.5 → 2) first on mobile, or repeat a one-line change summary above Accept.
3. **m (task 3, carried v4d #6): an unused fieldmap is silent.** The PA fieldmap has a B0FieldIdentifier but no IntendedFor and no B0FieldSource selecting it, and the inspector gives no hint. For a lab manager checking topup pairs this is the question. Add "Not selected by any run" to the inspector, and repeat it in Relationships.
4. **m (query, carried v4d #8):**
   - The operator select is truncated to "Column at" at 1440px.
   - Results do not show the participant attribute values (age), give no "images only" note and offer no copy/export.
   - At 390px each file is a 225px block that repeats PARTICIPANT/RELEASE. That is fine for 4 files and heavy for 200.
5. **m (access, carried v4d #7):**
   - There is no guidance on where to find a colleague's principal id.
   - "Read content" is off by default, and "Create grant" does not restate the actions. A "give Jane read access" request yields metadata-only unless the user notices.
6. **m (assessment detail):**
   - The TR finding does not say where the authored value came from (`from task-rest_bold.json`). The two key facts are not pinned at the top of the "16 recorded facts" grid, and fact names still break mid-word.
   - The events finding's "column 1" is ambiguous.
7. **m (layout/fidelity, carried):** the "Revoked …" pill wraps at 1440px. The revoke timestamp comes from the client clock but is styled like a server timestamp.
8. **m (terminology, live only):** live shows "Profile native-reference-v8" in the header and "reference-admission-v7" in the Assessment card (`live/shots-v4e-accept/chromium/assessment.png`). A user cannot tell how profile and admission policy relate, or whether v7 vs v8 is a mismatch. One line of explanation, or a single label, would help.
9. **taste:** a resolved finding keeps its amber "Recorded warning" chip. I would grey it or prefix it with "was". The nameless-path example ("such as sub-02_task-nback_bold.json") could use the current subject.

## Regressions

None found on the five task paths compared with v4d.
- Every route that worked in v4d still works.
- The broken subject-level route now works.
- The draft-reset regression from v4d is gone.
- The new 0-files warning and same-value tag add information without hiding any.

## Proposals

1. Echo preview warnings next to Accept, and demote Accept when a candidate changes no effective metadata.
2. On narrow screens, order the preview as change summary → Accept → findings → details. Or add a one-line "RepetitionTime 1.5 s → 2 s on 1 file · resolves MriRepetitionTimeMismatch" directly above Accept, at every width.
3. Fieldmap inspector: "Not selected by any run" for fieldmaps with no IntendedFor/B0FieldSource link.
4. Query: widen the operator select, add the participant attribute column, add "Images only" and Copy paths / Export TSV.
5. Access: a one-line hint on finding a principal id, and a Create button that states the actions ("Grant read metadata + content").
6. TR finding: add "authored in task-rest_bold.json", and pin `temporalSpacingSeconds` and `timingMetadata.RepetitionTime.*` at the top of the facts grid.

**Adoption verdict (data manager):** adopt.
- Tasks 1, 2 (both run- and subject-level), 4 and 5 completed first time at both widths, with clear messages. The correction preview now gives real decision support: what value changes, where it comes from, which finding resolves and why, and a receipt that proves identity.
- The v4d blocker for lab use (silent loss of a subject-level fix) is resolved.
- Remaining friction is training-level: the principal-id lookup, the unused-fieldmap silence, and the query export. One caution: before rollout, confirm on the real backend that subject-level sidecars are inherited as the demo shows.

**Unverified:**
- Subject-level inheritance and the v4f TR-value display on the live backend. The latest live capture I have is v4e, run-level only.
- Firefox, Safari and screen readers.
- Dark mode, beyond one inspector capture.
- The live B0 view, not re-run this round.
