# Sanctity build plan: a clean kernel, a working product, a convincing demo

Written 17 November 2026 from a first-hand read of the code, the console and the
tests. It turns the [product direction](product-direction.md) into concrete design
decisions and ordered work. Nothing below is claimed as implemented unless it
appears in "What exists today".

## 1. Summary

Sanctity's engine is ahead of its product. The kernel is sound and already close
to modality-neutral. Much of what it can do is reachable only from developer
demos, not from the service a person would sign in to. Getting to a convincing
demo is therefore mostly a matter of **surfacing and simplifying**, plus four
things that are new:

1. **Any data type has a home.** Replace "reject what is not understood" with
   three recorded levels of coverage, so EEG, MEG, PET, questionnaires and
   unstructured archives can all be stored, described, shared and exported.
2. **People can use it without an operator.** Institutional sign-in, upload from
   the browser, and adding new data to an existing dataset.
3. **The data is visible.** A participant-by-data-type grid, an in-browser image
   viewer, and tables shown with their data dictionaries.
4. **Anyone can run it.** One command starts a seeded instance.

The estimate is six to eight weeks of focused work to a pitch-quality demo, with
a first internal demo after about three. The basis: the entire current system,
about 15,000 lines and 47 commits, was built between 6 October and 2 November.

## 2. What exists today

Verified on 17 November 2026. The full gate passes on branch `back-in-shape`:
`sbt check` (520 tests) and `scripts/check-postgres.sh` (PostgreSQL integration,
crash and retry, coordinated restore).

| Area | Works today | Missing for a product |
| --- | --- | --- |
| Kernel (1,542 lines) | Content-addressed manifests; draft → candidate → release; admission rules; findings and checks; labs, projects, grants, current-access checks | See the seven changes in section 3 |
| Scientific layer | MRI and behavioural BIDS: names, inherited metadata, relationships, header and timing checks, corrections, views, header-QC and temporal-statistics derivatives | EEG, MEG, iEEG, NIRS and PET folders are rejected outright; no questionnaire or archive support |
| Service over HTTP | About 40 routes: releases, metadata, single-file download, corrections, acceptance, grants and shares, access explanations, frozen queries, lab and project directories, organization changes | Sign-in, dataset upload, export, views, processing runs and re-assessment have no routes |
| Console | Lab, project and dataset navigation; release, file, findings and relationships screens; fix-a-finding, preview and accept with conflict recovery; query builder; grant and revoke | Sign-in is a pasted token; no upload, no image viewer, no participant view; recipients are chosen by UUID |
| Operations | One Java 21 jar; PostgreSQL 17; filesystem store; verified backup and closed restore; deployment templates | No container image or compose file; about an hour of manual steps to get a running console with data |
| Tests | 520 unit and lifecycle tests; PostgreSQL integration; 81 console behaviour probes; accessibility checks; a real browser → HTTP → PostgreSQL rehearsal | Hosted CI has been red since 15 October (fixed on this branch; see section 8, package 0) |

**Capabilities that exist in the engine but not in the service.** Each of these is
built and tested, and reachable only from `modules/app` demos:

- Scientific views: a selected subset becomes its own shareable release.
- The behavioural view compiler.
- Derivative interpreters (header QC, temporal statistics) and processing runs.
- Full-release export to a directory.
- Re-assessing existing content under newer rules (`reinterpret`).
- Organization changes (members, stewards, dataset moves) have HTTP routes, but
  the console calls none of them.

**The console is better than a prototype.** It has a coherent visual language
(design tokens, light and dark themes, responsive layout), careful keyboard and
focus handling, and a strong test harness. It should be evolved, not replaced.
Its weaknesses are structural and are listed in section 6.

## 3. The clean kernel

### 3.1 The seven rules the kernel keeps

These already hold. Every extension below must preserve them.

1. Bytes are immutable and addressed by their SHA-256 digest.
2. A release is a manifest, an interpretation and an assessment, accepted together
   or not at all. History is never rewritten.
3. Every change (import, correction, view, derivative) goes through the same
   path: prepare, review a candidate, accept.
4. Meaning belongs to an interpretation that names the rules that produced it.
   Reading old data under new rules is a new, visible step, never a silent change.
5. Access is explicit, checked at the moment of use, explainable and revocable.
6. A selection is frozen. A shared subset is an ordinary release with its own
   access.
7. Everything leaves as standard files, in BIDS wherever a specification exists.

### 3.2 The vocabulary is already BIDS, and that is the right choice

The scientific facts in the kernel are BIDS-shaped, not MRI-shaped:

- a file has a participant, an optional session, a suffix, an extension and
  entities (`kernel/Query.scala:5-12`);
- metadata is resolved from sidecars, with the origin of each value recorded;
- relationships carry typed evidence;
- queries correlate files within a participant or a session.

Every BIDS data type fits this model. Adding the behavioural profile needed **no
database migration**, because its files are ordinary artifact facts. Sanctity
does not need a generic entity-attribute-value layer. BIDS is the vocabulary; the
archive level in 3.3 covers data that has no BIDS specification.

### 3.3 Seven changes

Each entry states the problem as the code reads today, the decision, and how to
tell it is done.

**K1. One registry chooses the interpreter.**
- *Problem.* The interpreter is fixed when the service is wired
  (`service/ApiServer.scala:39`, `service/RepositoryCli.scala:53`). The
  derivative interpreters and the behavioural view compiler can only be reached
  by constructing a different repository object in a demo.
- *Decision.* One `SemanticInterpreter` that dispatches on declared content and
  on the activity:
  - processing evidence selects the registered derivative recipe;
  - a root `dataset_description.json` selects BIDS;
  - a root `archive_description.json` selects the archive profile;
  - anything else is a finding that explains how to declare the kind.
- *Why this shape.* `prepare(manifest, activity, sources)` already receives
  everything needed, so `NativeRepository` and the catalog do not change. The
  kind is part of the versioned content, so exports describe themselves.
- *Done when.* The service accepts a raw BIDS dataset, a QC derivative and an
  archive through the same HTTP routes.

**K2. BIDS interpretation is composed from modalities.**
- *Problem.* `bids/BidsInterpreter.scala` mixes generic loading with MRI
  resolvers in one 250-line function. `RepositoryBidsInterpreter` is an if/else
  over two cases. Lists of "what this modality allows" are scattered, for example
  the editable companions in `application/NativeRepository.scala:54`.
- *Decision.* A common core handles names, inherited metadata, participants and
  facts. Each modality contributes:
  - its datatype names;
  - its relationship rules and content checks;
  - which authored files a curator may correct;
  - its table families and view rules.

  MRI and behavioural are extracted from existing code, not rewritten. This is
  task F01 of the [multimodal plan](multimodal-bids-plan.md), reduced to what
  two existing modalities need.
- *Done when.* Existing MRI and behavioural fixtures produce the same findings
  and facts as before, and adding a modality touches only its own file plus one
  registration line.

**K3. Coverage is graded, not all-or-nothing.**
- *Problem.* `bids/RepositoryBidsInterpreter.scala:16` raises an error for any
  file under `eeg`, `meg`, `ieeg`, `nirs` or `pet`, which blocks acceptance of the
  whole dataset.
- *Decision.* Record one of three levels for every file, and summarize them per
  data type:

  | Level | What Sanctity has done | What it claims |
  | --- | --- | --- |
  | **Preserved** | Stored the bytes, digest and path | Nothing about meaning |
  | **Described** | Checked the BIDS name, resolved sidecar metadata, indexed it for queries | The description is consistent; the recording itself was not opened |
  | **Assessed** | Ran the modality's content checks | The stated checks passed, each with its inputs and rule version |

  - An admission policy decides whether Preserved content is allowed
    (`inclusive`, the default) or blocks acceptance (`strict`, today's behaviour).
  - EEG, MEG, iEEG, NIRS, PET and motion reach **Described** by adding datatype
    rules, the same mechanism `RepositoryBidsContext` already uses for `beh`.
  - The **archive profile** is a dataset whose root has an
    `archive_description.json` (title, creators, description, licence, subjects,
    aligned with DataCite fields). Its files are Preserved.
- *What this changes.* The multimodal plan deliberately deferred an
  "archival-only admission policy". This is that policy. A Preserved or Described
  file never receives a validity badge, and the console shows the level beside
  every data type.
- *Done when.* A mixed MRI and EEG dataset is accepted and shows "MRI: assessed,
  EEG: described", and a folder of arbitrary files is accepted as an archive.

**K4. Tables carry their dictionaries.**
- *Problem.* `ParticipantFacts` holds only `participants.tsv` as text fields.
  `phenotype/` is ignored, and variable descriptions, units and coded levels are
  not interpreted.
- *Decision.* Treat `participants.tsv`, `sessions.tsv`, `scans.tsv` and
  `phenotype/*.tsv` as one kind of fact:
  - a table name and its key (participant, optional session);
  - variables, each with its dictionary entry (description, units, levels);
  - values kept as written, with a numeric form where they parse.

  Queries gain variable conditions that name their table. Disclosure for views
  becomes a map from table to permitted columns.
- *REDCap.* A small adapter converts a REDCap export and its data dictionary
  into phenotype tables with JSON dictionaries. REDCap stays the capture tool.
- *Done when.* "Participants with resting-state BOLD, an EEG recording and a MoCA
  total below 26" returns a frozen selection.

**K5. Freeze the identity ladder.**
- *Problem.* An interpretation's identity is computed by one of seven encodings,
  chosen by which optional parts are present (`kernel/Science.scala:79-88`).
  Every read recomputes it, so all seven must stay executable forever.
  Disclosure identity follows the same pattern (`kernel/Projection.scala:6-19`).
  K3 and K4 would each add another rung.
- *Decision.* Move the existing encodings into frozen legacy functions, protected
  by the [historical compatibility corpus](upgrade-compatibility.md). New
  interpretations use one encoding made of named, versioned sections, each hashed
  separately. A new kind of fact adds a section and changes nothing else.
- *Done when.* The compatibility corpus passes unchanged, and K3 and K4 add
  sections without touching the top-level encoding.

**K6. Upgrades do not block curation.**
- *Problem.* A profile's identity includes a fingerprint of the source code of
  four modules and `build.sbt` (`build.sbt:40-59`). Corrections and views are
  refused when the stored profile differs from the current one
  (`application/NativeRepository.scala:157-159`). The remedy, `reinterpret`,
  has no HTTP route and no command. As the code reads, after any upgrade that
  touches those modules a curator cannot correct an existing dataset. This was
  found by reading the code, not by running an upgrade.
- *Decision.*
  - Expose re-assessment over HTTP and in the console, showing what the new
    rules read differently before anyone accepts it.
  - When only the build fingerprint differs, re-interpret the base
    automatically. If the result is otherwise identical, proceed and record the
    re-verification. If it differs, require the explicit step.
  - Keep recording fingerprints as provenance.
- *Done when.* A dataset accepted under an older build can be corrected under a
  newer one without operator action, and a real change in reading is shown as a
  reviewable difference.

**K7. A dataset can grow.**
- *Problem.* Import requires an empty dataset
  (`application/NativeRepository.scala:27`). File edits are move and remove only.
  A study that acquires a new session cannot add it.
- *Decision.* Import against a base release: the base manifest plus the new
  files becomes a candidate, the preview shows added content and new findings,
  and acceptance makes it the next release. Replacing an existing file requires
  explicit confirmation.
- *Done when.* A second session is uploaded into an existing dataset and the
  previous release remains retrievable exactly.

### 3.4 Limits to lift before a pilot, not before the demo

- Imports, edits and views stop at 10,000 entries
  (`NativeRepository.scala:17` and five other places).
- All JSON metadata together must fit in 32 MiB (`BidsInterpreter.scala:17-18`).
- Release inventories are returned whole, without paging.

A study of a few hundred participants exceeds the first limit. Lift these with
measurement against the frozen [workload budgets](engineering-workload.md), after
the demo.

## 4. The product surface

Each row is an existing capability to expose or a small new one.

| Need | Today | Work |
| --- | --- | --- |
| Sign in | Operator-issued bearer token, pasted | OIDC code flow with PKCE, server sessions in a protected cookie, a binding from provider identity to principal (motes F04.1, F04.2). Test against a bundled development provider; qualify the institution's provider during the pilot |
| Find people | Recipients are principal UUIDs (`ui/app.js:1692`) | A directory of people by name, limited to what the caller may see |
| Create and upload | Command line only | Create a dataset in a project; an upload session that stores each file as a blob, then runs the existing durable import; K7 for additions |
| See the data | File tree and metadata | A coverage read (participants × sessions × data types), a first-volume preview for 4D images, inline file serving |
| Share a subset | Whole-dataset or fixed-release grants | Expose scientific views: selection → new release → share. This is the strongest differentiator |
| Export | One file at a time | Streamed tar of a release or a selection, with a receipt; the same from the command line |
| Re-assess | Not reachable | K6 |
| Run it | About an hour by hand | Container image, compose file (PostgreSQL, Sanctity, development identity provider), `demo seed` command (motes L01.2, L01.3) |
| Watch it | Nothing | Liveness and readiness routes, a request log (mote O02.1) |

Processing runs over HTTP and DICOM intake through Orthanc wait until after the
pitch.

## 5. Data types in the demo

| Data | Level in the demo | How |
| --- | --- | --- |
| MRI (anat, func, dwi, fmap) | Assessed | Exists |
| Behavioural tables and events | Assessed | Exists |
| EEG and MEG | Described | K3 datatype rules |
| Questionnaires and clinical scores | Assessed as tables | K4, with the REDCap adapter |
| Anything else (genomics files, video, vendor raw) | Preserved | K3 archive profile |

Depth is added per data type only when a real collection needs it, as the
[vision](vision.md) already says.

## 6. The console

**Keep.** The visual language, the design tokens and themes, the focus and
announcement handling, the correction → preview → accept flow, and the Playwright
and accessibility harness.

**Restructure.**
- `ui/app.js` is one 1,834-line closure. Split it into ES modules. This needs no
  bundler and passes the existing content security policy (`script-src 'self'`).
- New stateful views (grid, viewer, upload queue) need a component layer.
  Recommended: Preact with `htm`, vendored as two files, with JSDoc types checked
  by `tsc` in CI. This keeps the single-jar, no-build deployment. Fall back to a
  bundler and React only if the lean path gets in the way.
- `ui/demo.js` re-implements the backend in 400 lines so tests can run without a
  server. Every new feature must be written twice. Replace it with fixtures
  recorded from the seeded instance.
- The grant list is read from browser storage, not from the API
  (`ui/app.js:195`). Fix it.

**Speak the user's language.**
- Findings and rules appear as code names (`MriTransformUnavailable`,
  `bids-mri-header-consistency-v1`). Give each a title, a one-line explanation
  and a suggested fix.
- Digests and UUIDs lead most screens. Move them behind a "Provenance"
  disclosure.

**Add.**
- Sign-in with the institutional provider.
- Upload: drop a folder, watch progress, read findings, fix, accept.
- Dataset overview: the participant × data-type grid with coverage levels.
- Viewers: NIfTI images (NiiVue), tables with their dictionaries, JSON.
- Share dialog: pick people by name, choose a scope, see why access holds, revoke.
- Members and stewards, using the organization routes that already exist.
- History: compare two releases.

**Check first.** Whether NiiVue runs under the console's content security policy
(it may need WebAssembly or worker permissions) is not verified. Spend the first
half day of the viewer work on that question.

## 7. The demonstration

**Instance.** `docker compose up`, then `sanctity demo seed`. Two labs, each with
a project and two or three named people, signed in through the bundled identity
provider.

**Data.** Two options:
- *Public data (default).* The multimodal face-processing dataset `ds000117`
  (MRI, MEG, EEG and behaviour, CC0), which the test suite already uses in
  excerpt, plus a questionnaire table marked as synthetic, plus one archive
  collection.
- *The institution's own data.* A de-identified pilot study from a volunteer
  lab. More persuasive, and it needs a check with the research ethics office
  first.

**Script, about ten minutes.**

| # | Beat | What the audience sees | Depends on |
| --- | --- | --- | --- |
| 1 | Sign in | The institutional sign-in page, then "your labs and projects" | Sign-in |
| 2 | See a study | Participant × data-type grid: MRI assessed, EEG described, questionnaires | K3, K4, grid |
| 3 | Look at data | A T1 image in the browser; a questionnaire with its dictionary | Viewers |
| 4 | New data arrives | Drop a session folder; a finding blocks a malformed events file | Upload, K7 |
| 5 | Fix it | Fix in place, preview the effect, accept; the earlier release is still there | Exists |
| 6 | Ask a question | "Resting BOLD, EEG, and MoCA below 26" → frozen selection | K4 |
| 7 | Collaborate | Share that subset with the other lab by name; sign in as them; revoke; it is gone | Views, people directory |
| 8 | Leave | Export exact BIDS, ready for deposit anywhere | Export |
| 9 | Anything else | Drop a folder of unstructured files into an archive collection | K3 |
| 10 | Trust | Show a restore drill result: data and revocations survive | Exists |

Rehearse on the real stack, not the synthetic transport, and keep a recording as
a fallback.

## 8. Work packages

Estimates are working days for one experienced developer directing coding
agents. They are estimates, to be revised after stage 1.

**Stage 0: back in shape (17 November)**

| # | Package | State |
| --- | --- | --- |
| 0 | Commit the October scientific work; record tracker operations; fix the CI step that failed on coloured sbt output | Done on branch `back-in-shape`; needs merging and a push to confirm hosted CI |

**Stage 1: anyone can run it, anything can be stored (about 3 weeks)**

| # | Package | Days | Needs |
| --- | --- | --- | --- |
| 1 | One-command instance and seed | 2–3 | — |
| 2 | K1 registry and K2 composition; wire views and derivatives into the service | 3–4 | — |
| 3 | K5 sectioned identity; K6 re-assessment | 3–4 | 2 |
| 4 | K3 coverage levels, Described datatypes, archive profile | 3–4 | 3 |
| 5 | Upload over HTTP; K7 additions | 4–5 | 2 |
| 6 | Sign-in, sessions, people directory | 4–5 | 1 |
| 7 | Console modules; sign-in and upload screens; coverage chips | 4–5 | 5, 6 |

*Exit:* sign in, create a dataset, upload MRI and EEG, see findings, fix, accept,
and store an archive, all in the browser on a freshly started instance.

**Stage 2: the multi-modal story (about 3 weeks)**

| # | Package | Days | Needs |
| --- | --- | --- | --- |
| 8 | K4 tables, dictionaries, variable queries, REDCap adapter | 4–5 | 3 |
| 9 | Coverage read and participant grid | 3 | 4 |
| 10 | Viewers: images, tables, JSON | 3–4 | 7 |
| 11 | Share a subset: views over HTTP, share dialog, grants from the API | 4–5 | 2, 6 |
| 12 | Export as a streamed archive, with receipt | 2 | — |
| 13 | Plain-language findings; provenance disclosure; release comparison | 3 | 7 |

*Exit:* the full script in section 7 runs on the real stack.

**Stage 3: ready to pitch (1–2 weeks)**

| # | Package | Days |
| --- | --- | --- |
| 14 | Seeded two-lab demo data; rehearsals; recorded fallback | 3 |
| 15 | Health routes; restore drill shown; a two-page security overview for research IT and the privacy office | 3 |
| 16 | The brief, the cost table, a pilot proposal, statements of interest from two or three investigators | 2–3 |

**After the pitch: pilot.** S3-compatible storage; lifting the 10,000-entry
limit with paging; qualifying the institution's identity provider; privacy
review; DICOM intake through Orthanc; assessed depth for EEG; processing runs
over HTTP.

**Three parallel tracks**, so that interns and agents do not collide:

- **A, kernel and service:** packages 2, 3, 4, 5, 8, 11, 12.
- **B, console:** packages 7, 9, 10, 13.
- **C, instance, data and operations:** packages 1, 6, 14, 15.

A consultant's time is best spent reviewing package 6 (sign-in and sessions) and
the deployment at the end of stage 1.

## 9. Convincing the Vice-President, Research

A demo alone will not carry the decision. The case has six parts, and the demo is
evidence for the first two.

| Claim | Evidence |
| --- | --- |
| It meets our needs | The demo: several labs, several data types, controlled sharing |
| It is real | It runs on our hardware today; it was built in weeks, not years |
| It costs less and the spending is staged | The [cost comparison](product-direction.md#6-cost-comparison-the-indoc-quote-versus-building-in-house): roughly one-third to two-thirds of the quote over five years, released phase by phase |
| It is safe | Institutional sign-in; access that can be explained and revoked; tested restore; a planned privacy review, starting with de-identified data |
| It will last | A small codebase on a standard stack, with tests and runbooks; a named partner in research IT; data that exports as standard BIDS if the effort ever stops |
| Researchers want it | Statements from two or three investigators who will pilot it |

**Institutional obligations.** The Tri-Agency Research Data Management Policy
requires research hospitals that administer agency funds to maintain a public
institutional data-management strategy. Its data-deposit requirement is being
phased in and, according to a secondary source, was not yet in force in 2026
([policy](https://www.science.gc.ca/site/science/en/interagency-research-funding/policies-and-guidelines/research-data-management/tri-agency-research-data-management-policy)).
Confirm the current status before using this in the pitch. Sanctity gives the
institution a concrete answer to "where is research data managed and how does it
reach a repository".

**Pitch in two steps.**

1. **Now: ask for a mandate, not a decision.** Show the existing console and a
   one-page brief. Ask for eight weeks and a small budget to produce the demo in
   section 7, and ask that no commitment to an external platform be made in the
   meantime.
2. **At the end of stage 3: ask for the pilot.** Run the demo. Present the cost
   table, the pilot proposal and the investigators' statements.

**Say when the other answer would be right.** The pitch is more credible if it
names the conditions under which an external platform is the better choice:

- the institution needs regulated clinical-trial data capture or linkage to
  provincial administrative data as a primary use;
- no research-IT partner can be named to operate the service;
- the stage 1 exit is not met within the time requested.

## 10. Working rules until the pitch

- **No new hardening or qualification scope.** Recovery, the policy witness and
  compatibility work receive reliability fixes only.
- **One gate per change:** `sbt check`, `scripts/check-postgres.sh` and the
  console checks, on a green hosted CI. Evidence lives in commits and CI runs,
  not in new narrative documents.
- **Every package ends in something a person can do in the browser.**
- **The reference catalog is the test oracle.** New service features need the
  PostgreSQL implementation and the shared lifecycle suite.
- **Legacy identities are frozen.** The compatibility corpus is the only guard
  they need.
- **The seven in-progress multimodal motes** (F01, F03, F05, B01–B03, T01) are
  paused and re-scoped under packages 2, 4 and 8.

## 11. Decisions for the product owner

Each has a default, which will be used unless changed.

| Decision | Default |
| --- | --- |
| Demo data | Public `ds000117` plus synthetic questionnaires; switch to a de-identified in-house study if ethics clearance is quick |
| Admission policy | `inclusive`: Preserved and Described content is accepted and labelled |
| Console component layer | Preact with `htm`, no bundler |
| Development identity provider | Keycloak in the compose file |
| When to approach the Vice-President | Now, for the eight-week mandate |
