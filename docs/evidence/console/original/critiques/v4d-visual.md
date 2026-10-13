## Scores

| dimension | score | justification |
|---|---|---|
| typography | 8 | The system is unchanged and disciplined: serif display, sans body, mono for paths and ids. Origins at 390 now fit on one line: "Inherited from task-nback_bold.json" measures 19px on all rows, which fixes v3 issue 2. New defect: at 390 the digest-chip role label "metadata-1" breaks at its hyphen into "metadata-" / "1" (release, assessment; 2 and 10 wrapped role labels, measured). Check names also break at hyphens ("bids-mri-header-" / "consistency-v1"). At 390 the query table shreds paths into 6–7 lines, including "bold.ni" / "i.gz". |
| color | 9 | Still restrained. Indigo is the only accent. Amber and red appear only on findings and verdicts. The NEW, UNCHANGED, RESOLVED and NOT EVALUATED tags are quiet outlined caps. The blocked card's red border and red `!` sidecar line read as one story in both themes. Dark mode (all 9 specimens at 1360 and 390) is coherent, with no glare washes. |
| layout | 8 | The release at 1360 and 1440 is well composed. Assessment is balanced: the verdict and three finding cards on the left, three collapsed rule families on the right. Relationships use clean full-width lists. Weak: the correction columns now end about 340px apart (left at y≈1157, right at y≈1500 at 1440), against 35px in v3. Query is still a lone 6-row table with a floating "New query" button. |
| hierarchy | 8 | The release header, tabs and inspector read in the right order. In the live inspector, a Findings block now leads the file (live mobile-inspect.png), which puts first what a curator needs. The correction preview leads with Candidate and the verdict on mobile. The inspector still stacks Effective metadata, Content checks and Linked acquisitions at equal weight, though it is now shorter. |
| components | 8 | The rule-family disclosure ("1 with findings" or "All consistent", plus a chevron) is a good, reusable component. "Show 24 files" collapses long lists (live preview.png). Arming revoke no longer reflows the row (62 → 62px, measured, Cancel focused). The actions cell now sits on 2 lines (v3: 3). Weak: the hyphen-broken role labels in digest chips, and an underlined proportional link style in the query table. |
| function | 8 | Gate exit 0 (log tail). 36 captures (9 specimens × 1360/390 × light/dark): all reached `ready`, with 0 page or console errors and 0 horizontal page overflow. Confirmed defect: at 390 the session-grants table scrolls inside `.scroll-x`. The table is 601px wide in a 356px box, so the row's Revoke button sits at x=540–606, off-screen, with no scroll cue. That is a core task (revoke access) hidden on phones. It is unchanged from v3c, so it is not a regression. |
| themes | 9 | Dark mode is verified at 1360 and 390 on all specimens. Blocked correction at 390 dark: the red outline, the "Blocks acceptance" label and the `!` line are legible, and the disabled Accept is clearly disabled. The light/dark token parity holds. |
| mobile | 7 | Release at 390 is 2565px (v3 3242px), and the folded "Files · 60 / path / Browse" bar is a good move. Live inspector is 2446px. Assessment grew to 1593px (v3 1320px) because finding cards are now full. Confirmed failures: the query table at 390 wraps each path to 136–156px tall (6–7 lines, broken mid-extension). The access table hides Revoke off-canvas. Role labels split at the hyphen. The header still stacks 3 identity rows, so the tabs start at y=525. |
| identity | 8 | The provenance vocabulary is coherent: Inherited from / This file's sidecar / OVERRIDDEN / EFFECTIVE / Re-checked because / Matches preview / Declared by. It reads as one instrument. The connect hero is still the strongest single screen. The body is still "well-made cards", with no signature move beyond the cascade pips and dashed digest chips. |
| fidelity | 9 | The demo is now internally consistent with the live backend vocabulary. The warnings count 4 equals 3 cards (one ×2). 28 content checks equals 20 + 4 + 4. The fieldmap direction matches the subtitle "fieldmaps whose IntendedFor names it". The live B0 view shows the identifier, the run, both PA/AP sources and the declaring sidecars. "Same size, new content" and "New file" replace "157 B → 157 B". Nits: the grouped message "alignment row … column …" reads as truncated text, not as elided numbers. A NOT EVALUATED finding is listed under the heading "No longer reported", which a reader can mistake for resolved. |

## Previous round

v3 visual issues, re-checked on v4d:

1. Access Actions wraps to 3 lines / armed row grows: **fixed**. It is now 2 lines, and the armed row stays at 62px (measured at 1360). New observation: at 390 the Revoke button is off-canvas (see Issue 1). This was already true in v3c and was missed last round.
2. Origin splits at 390: **fixed**. All rows are a single 19px line.
3. Release header at 390 (3 identity rows): **not addressed**. The tabs start at y=525.
4. Check card heading in proportional link type: **fixed**. Assessment rule-family headings are mono.
5. Query page (all-"—" SESSION, underlined mono links, floating New query): **not addressed**, and it is worse at 390 than v3 noted (Issue 2).
6. Inspector length: **improved**. Release at 390 dropped from 3242 to 2565px, mostly from the folded tree. Consistent checks in the inspector are still shown in full, each with its own chip set.
7. "157 B → 157 B": **fixed**. It now reads "Same size, new content", and the live run shows "New file".

## Claims

- "Gate v4d exit 0": **verified** from the log tail (layout shift PASS, syntax PASS, gate exit 0). Not re-run.
- "Assessment ~900px live": **verified** from the live log (900px desktop, 1347px mobile). The demo assessment is 948px at 1360 and 1593px at 390 (measured).
- "Fieldmaps relabelled 'Fieldmaps by run', run → fieldmap": **verified** visually in the demo and the live shots. The arrow label "corrected by" is **unverified**; I saw only a plain arrow, so it may be an aria label.
- "Findings group by message shape with per-file counts; lists of more than 4 files collapse": **verified**. "× 2" groups appear in the demo, and the live preview shows "× 24 · Show 24 files".
- "Mobile tree folds to a one-line disclosure": **verified** (release-mobile-full.png, live mobile-inspect.png).
- "Content row reads 'Same size, new content' / 'New file'": **verified**.
- "Add sidecar on images without one": **verified visually** in live mobile-inspect.png. I did not drive it.
- "False Superseded fixed (Resolved tag)": **partly verified**. The demo shows RESOLVED. The live log for the add-sidecar run reports "0 new, 24 unchanged, 0 no longer reported" and "mismatch tag: absent", so the live shot does not show a Resolved tag. The claimed TR-mismatch resolution is not visible in the evidence I reviewed.
- "B0 field sources view on real data": **verified** (live/shots-b0/chromium/relationships.png). It is unobserved in the demo by design.
- "Zero console errors in 3 engines": **unverified** by me. My 36 Chromium captures show 0 errors.
- Evidence hygiene: live/shots-v4d/chromium/fail-add-sidecar-accept.png is byte-identical to inspect-bold.png and is older (11:47). It is a leftover from an earlier failed attempt, not a current failure, but it is misleading in an evidence folder.

## Issues

1. **Revoke is unreachable-looking at 390 (medium, factual).** The grants table is 601px wide inside a 356px `.scroll-x`. Revoke sits at x=540–606 and the ACTIONS column is clipped mid-word ("Read con"), with no scroll cue. Evidence: v4d-visual-work/shots/access-390-light.png and v3c-access-390.png (identical). Fix: below about 560px, render each grant as a stacked definition block (id · recipient · scope · actions) with Revoke full-width underneath. Alternatively, drop the Grant/Recipient chip columns into one line.
2. **The query table shreds paths at 390 (medium, factual).** File links are 136–156px tall (6–7 lines) and break inside "bold.ni" / "i.gz", while a SESSION column of "—" takes about 80px. Evidence: query-390-light.png (same in v3c). Fix: hide all-empty columns, and at narrow widths use one row per file (path on its own line, participant · release meta beneath). Use `overflow-wrap:anywhere` only as a last resort, with path-segment break hints (`<wbr>` after `/`).
3. **Digest-chip role label breaks at the hyphen (low-medium, factual).** "metadata-" / "1" at 390 (release-390-checks-crop.png). Fix: `white-space:nowrap` on the role span, and let the path span wrap.
4. **Correction columns are unbalanced at 1440 (low).** The left column ends about 340px above the right. Fix: make the left column sticky (`position:sticky; top`) so the diff stays beside the long findings list. Alternatively, move Content changes under Candidate.
5. **Grouped message elision reads as truncation (low, fidelity-adjacent).** "review timing alignment row … column …". Fix: render placeholders as `row N, column M` in a muted mono style, or append "(varies by row)".
6. **NOT EVALUATED under "No longer reported" (low).** Accurate, but an outcome that is not a resolution sits beside RESOLVED. Fix: rename the heading "No longer reported or not re-evaluated", or move not-evaluated findings into their own small group.
7. **Release header at 390 (low, carried over).** Three stacked identity rows push the tabs to y=525. Fix: one mono line `manifest 245b72 · interp fcad48 · assess 44e22c`.
8. **Query page at desktop (low, carried over).** The all-"—" column, underlined mono links, and "New query" floating mid-meta remain.
9. **Live B0 row (low).** The provenance line breaks mid-path ("sub-02_task-" / "rest_bold.json"). The identifier column is used only by this group, which is acceptable. Group order differs between live (DWI first) and demo (Fieldmaps first). Pick a fixed order.

## Regressions

- **Assessment at 390: 1320 → 1593px.** Finding cards now show full messages and file links. That is a reasonable trade for a better view of the findings, but it is a measurable length increase.
- **Correction column balance: 35px → about 340px** gap at 1440, caused by richer finding cards (cause lines, grouped files). This is a minor composition loss.
- No functional or visual-state regressions found. Access at 390 and query at 390 are identical in v3c and v4d (measured), so issues 1 and 2 are long-standing, not new.

## Proposals

- **One responsive pattern for every table at narrow widths** (grants, query results, content changes). Stack each row as a card with mono key lines. This would fix issues 1 and 2 together and keep the system simple.
- **One identity line** (header at 390, candidate, receipt). This is carried over and is still the cheapest mobile gain, at about 80px.
- **Collapse consistent checks in the inspector** with the existing rule-family disclosure. It is already built for Assessment, and reusing it would cut about 300px from release at 390 and make the inspector lead with what is wrong.
- **Signature detail (taste):** mute the `sub-XX_` entity prefix inside subject folders and in relationship rows, so curators scan by suffix. The relationships page is the place where this would matter most.

Evidence: critiques/v4d-visual-work/shots/ (36 captures `<page>-<1360|390>-<light|dark>.png`, plus access-armed-1360.png, release-390-checks-crop.png, v4d/v3c-{query,access}-390.png), and the scripts cap.js, probe.js, probe2.js, probe3.js.
