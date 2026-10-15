# Sanctity multimodal BIDS implementation plan

14 October 2026. Implementation started; mote is the live status record.

The [mote map](evidence/multimodal/mote-map.json) records the epic and all 36 work
items with their dependencies. The first [behavioural implementation slice](behavioural-profile.md)
adds bounded tables, recorded dependencies, correction and scientific projection.
Its broader milestone qualification remains open.
The next [streaming table foundation](streaming-tables.md) adds bounded TSV/GZIP
assessment and large generated files through the same blob and receipt lifecycle;
physiology and eye-tracking interpretation remain subsequent work.

Extend Sanctity from its bounded MRI profiles to behavioural studies, eye-tracking
and physiological recordings, EEG, and MEG. A supported collection must survive
the complete repository journey: import, assessment, acceptance, discovery,
correction, selection, sharing, export and re-import, with its scientific
dependencies and historical evidence intact.

Deliver this through composable, versioned scientific profiles over the existing
repository lifecycle. Preserve the core model of immutable bytes, explicit
interpretation, atomic acceptance and current authority. Add modality-specific
rules and readers at the adapters, extending kernel contracts only where an
identified cross-modality requirement needs them.

This is an extension roadmap, not a change to the execution priority of the
existing core-hardening plan (`core-hardening-plan.md` in the working tree). Its recovery priority and outstanding
institutional gates remain in force. The short IDs below map to the live mote
records; existing core work retains its own IDs, criteria and status.

## Outcomes and scope

The first delivery covers already-BIDS raw datasets. Native vendor conversion,
signal processing, numerical QA, interactive waveform/gaze viewers, and new
derivative standards are subsequent projects. Metadata, tables, findings and
recording membership must be inspectable through the existing console in this
delivery. Supporting a raw format does not imply support for every acquisition
variant or every processing pipeline.

Use BIDS 1.11.2 as the proposed new specification baseline. P01 freezes the exact
schema, rule coverage and tool versions before implementation. Existing MRI
evidence uses a pinned 1.11.1 schema; keep it reproducible and qualify any changed
MRI behavior separately. Never rewrite an imported dataset's declared BIDS version
merely because a newer interpreter is available.

| Delivery | Initial supported scope | Explicit boundary |
| --- | --- | --- |
| Behavioural | `beh/` tables, applicable events and dictionaries, participants/sessions, local stimulus references | No image acquisition required; `_beh.tsv` may omit timing columns. Resolve events-only conformance in P01 before claiming that variant. |
| Physiology and eye-tracking | BIDS physiological/stimulus time series and physiological events in supported datatype folders; standalone eye-tracking in `beh/` and accompanying MRI/EEG/MEG | Support the declared coordinate and timing conventions; no gaze calibration, resampling, event detection or inferred clock alignment. |
| EEG | Continuous EDF/EDF+ and BrainVision recordings with their applicable metadata and tables | BDF, EEGLAB, discontinuous and epoched variants get separate qualification tranches. No automatic conversion. |
| MEG | Continuous FIF, including supported split recordings, and CTF `.ds` directories; applicable noise/calibration/coordinate companions | Additional vendor formats and acquisition variants remain explicitly unsupported until independently qualified. |
| Mixed studies | Supported profiles coexisting in one raw dataset, including simultaneous EEG carried inside MEG where allowed | Dataset association is separate from temporal synchronization. Cross-dataset links and multi-source merged views remain outside the first release. |

The modality definitions come from the BIDS chapters for
[behavioural experiments](https://bids-specification.readthedocs.io/en/v1.11.2/modality-specific-files/behavioral-experiments.html),
[physiological recordings and eye-tracking](https://bids-specification.readthedocs.io/en/stable/modality-specific-files/physiological-recordings.html),
[EEG](https://bids-specification.readthedocs.io/en/stable/modality-specific-files/electroencephalography.html)
and [MEG](https://bids-specification.readthedocs.io/en/v1.11.2/modality-specific-files/magnetoencephalography.html).
The narrower format and operation boundaries above are Sanctity delivery choices.

### What supported means

Record these capabilities separately for each profile and format. A successful
byte copy must never produce a general scientific-validity badge.

| Capability | Required evidence |
| --- | --- |
| Preservation | Every inventoried file and directory is accounted for; full export reproduces original paths and file digests. |
| Interpretation | Names, effective metadata, recording membership and dependency evidence are deterministic and versioned. |
| Assessment | Each applicable check reports pass, finding, unsupported or not assessed, with exact input digests and rule identity. |
| Discovery | Indexed facts have defined semantics; SQL and reference queries agree; frozen selections retain membership. |
| Curation | Allowed corrections and grouped edits show their impacts, re-assess all affected dependencies and use ordinary atomic acceptance. |
| Scientific export | A selected recording exports with its required dependencies and approved fields; re-import preserves the supported meaning. |
| Processing readiness | Reported only for a separately named recipe and its requirements. New raw profiles have no default processing-ready claim. |

Unknown scientific files remain visible in inventory and coverage reports. A
candidate containing an unsupported scientific recording cannot be accepted under
the qualified profile as though fully assessed. Preserve the staged source and
explain the blocker. Existing accepted releases stay readable under their recorded
coverage. A separate archival-only admission policy would require its own scope;
it is not silently introduced here.

## Starting architecture and required changes

These are the current seams to use; names below describe present code, not new APIs
that have already been implemented.

| Area | Existing seam and limitation | Planned treatment |
| --- | --- | --- |
| Naming and inheritance | Pinned `bids4s` source; built-in registry covers MRI and selected MRI derivatives | Add portable datatype rules and deterministic companion resolution upstream; refresh the verified pin only after its JVM/JS and consumer checks. |
| Interpretation | [BidsInterpreter](../modules/bids/src/main/scala/sanctity/bids/BidsInterpreter.scala) combines common loading with MRI resolvers and checks | Compose common interpretation with explicitly selected scientific profiles; retain rule identities and coverage. |
| Recordings | [Manifest](../modules/kernel/src/main/scala/sanctity/kernel/Manifest.scala) has file identities and directory entries; scientific facts primarily identify files | Introduce an assessed recording description for a single file, linked files or a directory tree; preserve constituent file identities. |
| Dependencies | [Relationships](../modules/kernel/src/main/scala/sanctity/kernel/Relationships.scala) has generic relationship kinds but evidence and closure constraints | Reuse it where sufficient; version any added recording-member, binary-reference or directory-root evidence rather than forcing it into misleading companion evidence. |
| Events | [EventContentChecks](../modules/bids/src/main/scala/sanctity/bids/EventContentChecks.scala) assumes a BOLD image and NIfTI timing | Separate table rules, association rules and recording-specific timing observations. |
| Discovery | [Query](../modules/kernel/src/main/scala/sanctity/kernel/Query.scala) lacks an explicit datatype or logical-recording predicate | Add versioned datatype, recording-kind and role facts; keep physical-file queries and recording counts distinct. |
| Views | [ScientificViews](../modules/application/src/main/scala/sanctity/application/ScientificViews.scala) materializes small UTF-8 documents; [Projection](../modules/kernel/src/main/scala/sanctity/kernel/Projection.scala) embeds MRI-named disclosure identity | Preserve legacy identity encodings; add explicit table-family disclosure and a bounded streaming path for large generated tables. |
| Edits | [BidsChangeCompiler](../modules/bids/src/main/scala/sanctity/bids/BidsChangeCompiler.scala) restricts MRI formats/references | Dispatch to format-aware edits, including all recording members and supported embedded references. |
| Service | HTTP, public JSON, persistence codecs and console expose the current scientific contracts | Extend all representations together; no new facts that only the reference catalog understands. |

Keep `bids4s` responsible for portable BIDS semantics. Sanctity owns managed I/O,
content evidence, admission, authority, durable operations and publication.
Vendor readers belong in a bounded adapter, not the pure kernel or portable
Scala.js library. Avoid a general plugin framework: begin with an explicit profile
registry and extract shared code when at least two profiles need it.

## Delivery sequence

Owner roles below identify accountable disciplines, not assigned people. Assign an
individual when each task is scheduled. Each item closes only with its deliverable,
passing criteria and retained evidence; implementation alone does not close it.

| Milestone | Work | Exit |
| --- | --- | --- |
| M0 Scope and baseline | P01–P03 | Format/variant contracts, historical baseline, licensed corpus plan and reader decision are recorded. |
| M1 Shared foundation | F01–F06 and the reusable console/API work in X03 | Profiles compose, recording identity and queries persist, common tables are decoupled from MRI, historical compatibility passes. |
| M2 Behavioural delivery | B01–B04 | A permitted behavioural study completes the repository journey without neural data. |
| M3 Eye-tracking delivery | T01–T05 | Standalone and accompanying eye-tracking complete the same journey within measured streaming bounds. |
| M4 EEG delivery | E01–E05 | EDF and BrainVision both qualify, including linked-file integrity and independently readable exports. |
| M5 MEG delivery | G01–G05 | FIF/split FIF and CTF directory recordings both qualify with their applicable dependencies. |
| M6 Mixed studies | X01–X04 | A mixed collection supports exact queries, coherent exports, current access and recovery. |
| M7 Release qualification | Q01–Q04 | One identified packaged build passes the combined scientific and applicable core gates. |

The delivery preference is M2 → M3 → M4 → M5. Task dependencies below are the
actual blockers: EEG work does not require completion of the eye-tracking UI, and
reader/fixture work can proceed before every shared feature is complete. X03 starts
in M1 and each modality gate exercises its profile-specific presentation. No
calendar estimate is committed before P01–P03 resolve the fixture and reader risks.

## M0 Scope and baseline

### P01 Freeze profile coverage and policy

Owner: scientific/backend lead. Dependencies: none.

Deliver a coverage matrix keyed by specification version, datatype, format,
acquisition variant, operation and check. Include source clauses, admission severity,
resource limits and the reason for each repository-specific restriction.

Acceptance criteria:

- Every scope-table row has positive and negative fixture requirements and an
  explicit list of supported corrections, renames, removals and exports.
- Resolve the behavioural chapter's event usage against the generic events rule
  requiring corresponding data. Record the interpretation and validator result;
  do not manufacture an imaging dependency or silently claim unresolved variants.
- Distinguish BIDS requirements from repository policy and reader limitations.
  Missing recommended metadata is not automatically an error. Cross-dataset
  references have a visible unsupported outcome, never an automatic fetch.
- Freeze numeric parsing, clock, content-read and decompression budgets before
  qualification, using the engineering envelope described below. Name the exact
  schema and validator artifacts; preserve the previous MRI pins.

### P02 Establish fixtures and independent expected answers

Owner: scientific QA lead. Dependencies: P01.

Deliver a versioned corpus manifest and reusable comparison harness. Start with
MRI historical fixtures and behavioural data; add modality fixtures before their
implementation tasks close. Use small offline fixtures for ordinary tests and
separate acquired/stress corpora for qualification.

Acceptance criteria:

- Each source records permission/license, acquisition or synthetic origin, source
  revision and digests, transformations and retained-file hashes. No synthetic
  example is described as an acquired recording.
- Each supported format has at least one permitted acquired excerpt, one minimal
  valid synthetic case, and independently authored invalid/boundary cases before
  its modality gate. Missing licensed fixtures block that format's qualification.
- Expected paths, recording memberships, metadata origins, query answers and export
  digests are authored independently of the implementation being tested.
- Validator/reader invocations retain versions, configuration, stdout/stderr,
  outcomes and known differences. Normal unit tests remain offline.

### P03 Qualify the electrophysiology reader boundary

Owner: adapter/backend lead. Dependencies: P01, P02.

Deliver an executable spike for bounded EDF, BrainVision, FIF and CTF inspection,
plus a short decision record choosing the production reader approach per format.
Evaluate a supervised reader such as MNE alongside focused native parsing; do not
write an entire vendor stack merely to avoid an adapter dependency.

Acceptance criteria:

- For each format, demonstrate recording membership, channel order, sampling facts
  and readable sample ranges on a permitted fixture. Identify unsupported variants
  and any fields that cannot be observed reliably.
- Fix the reader protocol, runtime versions/licenses, timeout, memory/I/O limits,
  no-network execution, cancellation, and malformed-output handling. A failed
  inspection cannot become a successful assessment with absent observations.
- Bind observations to exact input blobs and reader identity; reject stale or
  substituted reports. Optional reader installation must not prevent MRI or
  behavioural operation, but its absence must block claims that require it.
- Keep an independent export-reading route for qualification. Reusing a production
  reader is an interoperability test, not an independent oracle for that reader's
  own parsing logic; add format-authored expected values or a second reader.

## M1 Shared foundation

### F01 Compose versioned scientific profiles

Owner: BIDS/backend lead. Dependencies: P01.

Deliver explicit common, MRI, behavioural, physiology, EEG and MEG profile seams
in the interpreter and a deterministic profile-selection contract.

Acceptance criteria:

- Mixed inventories invoke all applicable profiles; registry order cannot silently
  change classification or overwrite another profile's metadata/check result.
- Shared inheritance runs consistently across profiles. Ambiguous ownership and
  unsupported scientific files generate deterministic coverage findings.
- Assessment identity binds the selected profiles, their rule versions and registry
  context. Changing coverage requires explicit new interpretation, leaving old
  releases and assessments unchanged.
- Legacy MRI fixtures retain expected findings and facts, and stored historical
  identities remain readable unchanged. New execution binds the actual updated
  code fingerprints even when behavior matches; scientific rule changes get new
  rule/profile versions.
- Portable registry/resolver changes pass upstream JVM, Scala.js and public-consumer
  checks before the source pin is refreshed; Sanctity's default pinned build then
  passes the corresponding integration cases.

### F02 Represent logical recordings and their members

Owner: kernel/backend lead. Dependencies: P01, F01.

Deliver a recording description with a canonical root, member roles, required
members, ordering where meaningful, and evidence for every membership decision.
Use file identities for physical content; do not give one file identity to an
entire vendor directory or invent a file to represent it.

Acceptance criteria:

- Single-file, BrainVision triplet, split FIF and directory-root fixture descriptors
  have deterministic identities under inventory reordering; changing a required
  member or its bytes changes the assessed recording evidence.
- Missing members, conflicting ownership, duplicate split labels and unsafe member
  paths are detected. Shared companions can serve several recordings explicitly.
- A file selection and a recording selection have distinct documented semantics.
  Scientific export expands required membership before disclosure/authority checks;
  a partial family cannot silently export as a complete recording.
- Directory roots and internal files are handled in scans tables, relationship
  closure and inventories without requiring every vendor-internal filename to
  satisfy ordinary BIDS filename grammar. Any kernel change includes a versioned
  encoding and historical decoding path.

### F03 Separate table validation from recording timing

Owner: BIDS/scientific lead. Dependencies: F01.

Deliver reusable checked tables, events, column dictionaries, stimulus references
and recording-specific timing interfaces. Keep table syntax independent of NIfTI.

Acceptance criteria:

- Common table checks run without an image reader; MRI timing is supplied by the
  existing MRI adapter with equivalent findings under its existing profile.
- Preserve lexical values and missingness. Tests cover duplicate headers, ragged
  rows, invalid UTF-8, decimal bounds, missing values, permitted negative event
  onsets and overlapping events, and invalid negative durations.
- Distinguish known regular timing, explicit timestamps, segmented recordings and
  unavailable timing. Unknown origins/end times never become guessed values.
- Check evidence binds the table, selected metadata and any timing-bearing source.
  Generic event and stimulus rules follow the pinned
  [events specification](https://bids-specification.readthedocs.io/en/stable/modality-agnostic-files/events.html).

### F04 Extend discovery and wire contracts

Owner: query/persistence lead. Dependencies: F02.

Deliver datatype, recording kind, member role and coverage predicates, with
matching reference, SQL, request JSON and public response representations.

Acceptance criteria:

- Queries distinguish an eye-tracking recording from generic physiology even when
  both have suffix `physio`; directory-based MEG is discoverable as one recording.
- Independently expected cases cover same-participant/same-session combinations,
  sessionless data, zero-padded labels and absence. Matching different participants,
  sessions or datasets cannot satisfy a same-session conjunction.
- Recording counts do not multiply because a recording has several members or
  channels. File-level retrieval remains available and unambiguous.
- Frozen selection encodings are versioned; existing selections retain IDs and
  membership. A query needing unavailable historical facts reports that limitation
  rather than silently producing a misleading empty result.

### F05 Generalize scientific projection and disclosure

Owner: application/backend lead. Dependencies: F02, F03.

Deliver common projection composition and versioned field policies for events,
behavioural tables, channels, electrodes, coordinates and physiological columns.
Keep format-specific transformation decisions in their profiles.

Acceptance criteria:

- Every output file has a source-to-output receipt; copied bytes retain hashes and
  generated documents identify exact contributing source blobs and rule versions.
- A forbidden required field or dependency causes an actionable refusal. Optional
  fields and their dictionaries are removed coherently; no orphaned references or
  metadata for hidden columns remain.
- Coordinate metadata, units and applicable companion tables remain coherent.
  Dropping a channel row while retaining its binary signal is not an allowed
  disclosure transformation; binary channel removal is outside this release.
- Retain legacy `DisclosureProfile` digest behavior. A generalized policy gets a
  new encoding, with tests preventing collisions or reinterpretation of old IDs.
- Public text distinguishes field projection from de-identification. Embedded
  recording metadata is preserved and is included in the disclosure explanation;
  a sidecar mask does not claim to remove identifiers inside a vendor file.

### F06 Preserve stored scientific history

Owner: persistence/release lead. Dependencies: P02, F04, F05.

Deliver codecs, additive migrations where needed, and old/new package comparisons
for recording descriptions, query facts, profile evidence and projections.

Acceptance criteria:

- The retained old package creates the baseline before migration. New code reads
  it with identical historical release, manifest, interpretation, assessment and
  selection identities and exact exports.
- Explicit profile adoption creates a new interpretation/assessment without
  rewriting source blobs. New indexes never overwrite historical semantics.
- Reference and PostgreSQL lifecycle tests agree. Unsupported newer encodings fail
  clearly; restore and upgrade validate the required decoder/profile capabilities.
- All applicable MRI regression suites pass, including views, file edits, timing,
  processing input contracts and derivative publication.

## M2 Behavioural studies

### B01 Interpret standalone behavioural data

Owner: BIDS lead. Dependencies: F01, F03.

Deliver `beh/` registry rules and table/metadata assessment within the P01 coverage.

Acceptance criteria:

- A dataset with participants and behavioural task tables accepts without a neural
  recording. `_beh.tsv` without onset/duration remains valid within this profile;
  `_events.tsv` receives its own timing-column and association rules.
- Task/session/run entities, custom response columns, dictionaries and inherited
  metadata retain their values and origins. Missing optional metadata is handled
  according to the pinned specification.
- Invalid names/placement, malformed tables and ambiguous inheritance block the
  affected candidate with specific findings, while the accepted head is unchanged.

### B02 Resolve behavioural events and stimuli

Owner: BIDS lead. Dependencies: B01, F02.

Deliver behavioural table/event associations and local stimulus dependency evidence.

Acceptance criteria:

- Shared and overridden companions resolve by declared scope, not filesystem
  proximity. Two runs and two participants exercise both sharing and isolation.
- Local stimulus references are resolved from both supported behavioural tables
  and events; missing files, path traversal and ambiguous references are reported.
- A correction to a shared table or dictionary exposes every affected recording.
  HED text is preserved with coverage marked unvalidated; it is not discarded or
  presented as successfully validated HED.

### B03 Curate and export behavioural selections

Owner: application lead. Dependencies: B02, F05.

Deliver table/sidecar correction, supported rename/removal plans and behavioural views.

Acceptance criteria:

- An allowed correction passes through preview, conflict detection and ordinary
  acceptance; the preceding release, selection and export remain reproducible.
- A selected task/run exports applicable tables, approved dictionaries, local
  stimuli and projected participant/session/scan summaries with no dangling links.
- Removing a referenced stimulus or a required companion fails unless the same
  atomic plan repairs all retained references. Disallowed fields and unrelated
  stimuli are absent from the output.

### B04 Qualify the behavioural journey

Owner: QA/product lead. Dependencies: P02, B03, F04, F06, X03.

Deliver a reproducible behavioural demo and modality acceptance receipt.

Acceptance criteria:

- The real API/console completes import → findings → correction → acceptance →
  query → frozen selection → view → export → re-import on a permitted study.
- Reference and PostgreSQL tests cover each step, denied access, conflicting edits
  and rejected corrections. Re-import preserves expected scientific facts and
  dependencies; full export preserves exact original file bytes.
- The pinned validator comparison has no unexplained discrepancies, and all
  applicable common acceptance cases below pass.

## M3 Physiology and eye-tracking

### T01 Add bounded streaming table assessment and projection

Owner: I/O/application lead. Dependencies: P01, F03, F05.

Deliver streaming TSV/GZIP decoding and generated-table output through the existing
blob/receipt lifecycle. Keep small sidecar JSON bounded separately.

Acceptance criteria:

- Large table paths do not use whole-file strings, arrays or accumulated rows.
  Compression integrity, row/column/field limits, decompressed byte limits and
  bounded diagnostics are checked, including corruption near end-of-stream.
- An incomplete stream never yields an admissible completed check or published
  output. Cancellation, disk-full and reader errors clean up/recover through the
  existing staged-operation protocol.
- The 128 MiB and 1 GiB decoded stress cases meet the incremental-memory and
  cancellation budgets below; projection receipts bind the final generated blob.

### T02 Interpret physiological streams and timing

Owner: BIDS/scientific lead. Dependencies: F01, F02, F03, T01.

Deliver `physio`, `physioevents` and applicable continuous stimulus support, metadata
inheritance and association to supported recordings or behavioural contexts.

Acceptance criteria:

- Validate the headerless table layout against sidecar column declarations and the
  pinned timing/metadata rules. Missing sidecars and row-width disagreements fail.
- Preserve recording labels, sampling declarations, offsets and explicit clock
  evidence. Distinct streams can have different rates and origins without being
  forced onto a common sample grid.
- A shared physiological companion applies to the correct MRI echo family without
  becoming several independent recordings. Same task/run labels alone do not
  establish a synchronized relationship across datatype folders.

### T03 Add eye-tracking rules

Owner: BIDS/scientific lead. Dependencies: T02, P02.

Deliver the eye-tracking specialization, including recorded-eye identity, coordinate
meaning, units and conditionally required presentation metadata.

Acceptance criteria:

- Monocular, binocular and declared combined-eye fixtures resolve separate streams
  and appropriate metadata; gaze/timestamp columns and required units are checked.
- Screen-coordinate fixtures check the conditional presentation fields against the
  pinned specification. Missing pupil measurements are not converted into zeros.
- Tests cover malformed timestamps, missing samples, inconsistent declarations and
  physiological event sidecars. Preserve raw device timestamps and distinguish
  their units/origin from event seconds; no silent synchronization or interpolation.

### T04 Curate and export physiology and eye-tracking

Owner: application lead. Dependencies: T03, F05, B03.

Deliver streamed views, metadata corrections and supported table replacements.
The first version does not require a browser editor for millions of samples.

Acceptance criteria:

- Standalone `beh/` and accompanying recordings export with exact retained streams,
  applicable event/presentation metadata, references and required coordinate fields.
- Optional column removal streams through the table and updates `Columns` and
  dictionaries together; attempts to remove required columns fail before acceptance.
- Changing a sampling/clock declaration re-assesses affected observations. Replacing
  a large table creates new bytes and evidence while preserving previous releases.
- Full exports retain original compressed bytes. Transformed exports record new
  compressed blobs and verified row/column semantics instead of claiming byte identity.

### T05 Qualify eye-tracking and physiological journeys

Owner: QA/product lead. Dependencies: T04, P02, F06, X03.

Deliver standalone and MRI-accompanying demonstrations and acceptance receipts.

Acceptance criteria:

- Both demonstrations complete the B04 workflow and all applicable common cases.
  Independent table inspection confirms column order, missingness, row count and
  timing declarations before and after export.
- The pinned validator accepts supported exported cases or has individually
  explained, retained differences; no broad ignore setting hides new errors.
- Long recordings meet T01 budgets under cancellation and denied access, with no
  partial export represented as complete.

## M4 EEG

### E01 Share electrophysiology companion rules

Owner: BIDS/scientific lead. Dependencies: F01, F02, F03.

Deliver channel, electrode, coordinate and event associations shared where EEG and
MEG semantics agree. Keep format observations separate from authored metadata.

Acceptance criteria:

- Validate supported channel names/types/units/status and applicable coordinate
  metadata; distinguish channels from electrodes rather than requiring a universal
  one-to-one mapping.
- Shared coordinate/electrode documents retain their applicable scope. Different
  montages, sessions and recording labels do not inherit unrelated companions.
- Channel-referencing events resolve with their declared delimiter. Header/table
  disagreements are recorded with explicit admission policy and exact sources.

### E02 Support continuous EDF and EDF plus

Owner: electrophysiology adapter lead. Dependencies: E01, P03.

Deliver EDF/EDF+ recording recognition and bounded structural/header assessment.

Acceptance criteria:

- Reader observations include channel order, sample-rate facts and recording extent
  where available; annotation channels and per-channel rate differences are handled
  explicitly rather than flattened into an incorrect common rate.
- Truncation, impossible lengths, overflow and inconsistent declared counts produce
  deterministic findings. Discontinuous/unsupported variants identify the limit.
- Exact raw bytes survive full and path-preserving subset exports; a pinned external
  reader opens the output and agrees with independently expected observations.

### E03 Support BrainVision recording families

Owner: electrophysiology adapter lead. Dependencies: E01, P03.

Deliver `.vhdr`/`.vmrk`/`.eeg` membership and inspection of supported encodings and
binary layouts, including text-file references between members.

Acceptance criteria:

- The header and marker references resolve to the intended managed blobs, with
  evidence; a missing member, escaping path or conflicting reference blocks admission.
- Unsupported text encodings and binary layouts are explicit. The reader validates
  channel/sample facts against independent fixture expectations.
- Exporting one recording retains the full triplet. Allowed renames update all
  relevant text references with receipts and preserve raw signal bytes; the exported
  recording opens using its rewritten references.

### E04 Curate and export EEG

Owner: application lead. Dependencies: E02, E03, F05, B03.

Deliver channel/status, event and sidecar corrections, grouped removal and qualified
rename behavior, and an EEG view compiler using the common projection machinery.

Acceptance criteria:

- A change to channel status or metadata creates fresh assessed evidence without
  mutating the recording. Removing or renaming a required member alone is refused.
- Views include applicable channels, electrodes, coordinates, events, stimuli and
  selected physiology. Shared companion localization preserves effective meaning.
- An allowed table projection cannot imply removal of a binary signal channel.
  Unsupported embedded-reference rewriting is refused with a specific operation
  limit rather than producing a broken recording.

### E05 Qualify EEG journeys

Owner: QA/product lead. Dependencies: E04, P02, F06, X03.

Deliver separate EDF and BrainVision demonstrations and acceptance receipts.

Acceptance criteria:

- Each format completes the B04 workflow and common acceptance matrix on both
  catalogs, including inherited metadata and missing/corrupt-member failures.
- Export/re-import preserves channel order, supported sampling/timing facts,
  coordinates and dependencies; independent readers open all positive exports.
- The console exposes recording members and explains header-versus-sidecar
  discrepancies. It never offers an MRI processing recipe for an EEG recording.

## M5 MEG

### G01 Support FIF and split FIF recordings

Owner: electrophysiology adapter lead. Dependencies: E01, P03.

Deliver logical FIF recording recognition, ordered split membership and bounded
inspection of internal part references.

Acceptance criteria:

- Split recordings have deterministic ordered membership; missing, duplicate,
  conflicting or cyclic part references fail assessment within a fixed read budget.
- Channel/sample observations account for the full supported family rather than
  only its first part. Tests include reordered filesystem inventories.
- Path-preserving export retains the complete readable family. Renames requiring
  unqualified binary rewriting are explicitly unsupported; they are not necessary
  to claim the supported path-preserving export capability.

### G02 Support CTF directory recordings

Owner: electrophysiology adapter lead. Dependencies: E01, F02, P03.

Deliver `.ds` root recognition, complete internal inventory and structural assessment.

Acceptance criteria:

- The directory is one discoverable recording while every internal file retains
  its managed identity and digest. Required vendor members are format-validated;
  preserved optional internal files are not discarded as unknown BIDS filenames.
- Missing members, case/normalization collisions and symbolic-link inputs are
  rejected under existing intake/path policy; directories count toward intake limits.
- Full and path-preserving subset export reconstruct the directory exactly; an
  external reader opens it. A scans-table directory reference resolves correctly.

### G03 Resolve MEG coordinates and calibration dependencies

Owner: BIDS/scientific lead. Dependencies: G01, G02.

Deliver applicable coordinate, digitization, calibration, cross-talk and empty-room
relationships with exact selection evidence.

Acceptance criteria:

- Explicit empty-room associations resolve within the supported dataset scope,
  including another session or the reserved empty-room subject. No nearest-date
  association is invented.
- The coverage matrix distinguishes BIDS-required metadata, optional authored
  dependencies and recipe-specific calibration requirements. Missing optional
  processing calibration alone does not invalidate otherwise supported raw data.
- Missing declared targets, ambiguity and unsupported external references produce
  specific findings. Corrections/removals show every affected recording.
- Noise recordings are discoverable by role and do not inflate human-participant
  counts or require fabricated participant demographics.

### G04 Curate and export MEG

Owner: application lead. Dependencies: G03, F05, E04.

Deliver MEG views and bounded curation using complete recording families.

Acceptance criteria:

- Selecting a supported recording preserves its required family and applicable
  coordinate/calibration/noise dependencies; references point to retained targets.
- Participant/session/scan summaries handle dependency-only empty-room recordings
  and directory roots correctly. No unrelated participants or recordings appear.
- Edits to shared companions re-assess all dependants. Partial family removal fails;
  full removal can succeed only with coherent repairs to retained declarations.
- Unsafe renames of directory internals or binary references are refused before
  publication; path-preserving export remains independently readable.

### G05 Qualify MEG journeys

Owner: QA/product lead. Dependencies: G04, P02, F06, X03.

Deliver separate FIF/split-FIF and CTF demonstrations and acceptance receipts.

Acceptance criteria:

- Both formats complete the B04 workflow and common matrix; fixtures include
  cross-session empty-room dependencies, calibration and shared coordinates.
- Independent export reading confirms complete membership and expected observations;
  a damaged late split or internal directory member prevents false success.
- Queries and console counts distinguish logical recordings, files and participants.
  Supported exports pass the pinned validator comparison with explained differences.

## M6 Mixed studies and product integration

### X01 Represent cross-modality association without inventing synchronization

Owner: scientific/kernel lead. Dependencies: T04, E04, G04.

Deliver explicit association and clock-evidence contracts for mixed studies,
following the pinned [cross-modality guidance](https://bids-specification.readthedocs.io/en/stable/appendices/cross-modality-correspondence.html)
where applicable.

Acceptance criteria:

- Fixtures cover EEG plus eye-tracking, MRI plus physiology, and EEG channels
  acquired inside a MEG recording. The latter remains one native recording with
  appropriate channel kinds, not a fabricated second EEG file.
- Matching participant/session/task labels can establish only the documented
  association; synchronized timing requires explicit usable evidence with units,
  origin and provenance. Unknown synchronization is displayed as unknown.
- Views retain timing declarations and relevant event evidence without shifting
  samples, resampling signals or aligning clocks. Unsupported clock mappings
  block only operations that require those mappings.

### X02 Compose mixed queries and scientific views

Owner: query/application lead. Dependencies: X01, F04, F05.

Deliver one-dataset mixed selection and projection with shared dependency handling.

Acceptance criteria:

- An independently expected query finds participants with EEG and eye-tracking in
  the same session. Cases with split participants/sessions fail the conjunction.
- Selecting several recording kinds merges shared dependencies once, resolves
  output-path conflicts deterministically and preserves required metadata for each
  profile. Competing disclosure requirements cause a clear refusal.
- Metadata inheritance cannot leak between unrelated recordings after localization.
  A mixed exported dataset re-imports with the same supported memberships, facts
  and dependencies, allowing only receipt-declared transformations.

### X03 Expose profile capabilities in the API and console

Owner: HTTP/frontend lead. Dependencies: F01, F04, F05.

Deliver reusable recording, table and capability presentation in M1; exercise it
with each concrete modality as that profile lands.

Acceptance criteria:

- Users can filter by datatype/recording kind, inspect member files and effective
  metadata origins, page supported tables, inspect findings, and select a complete
  recording for export without treating it as an MRI image.
- Coverage, unsupported operations, unassessed content and available recipes are
  visible and consistent between API and UI. No generic green badge overstates a
  partial check or optional reader failure.
- Correction, conflict, cancellation, dependency expansion and disclosure refusal
  are understandable through keyboard-accessible screens; no hidden CLI-only step
  is required for the supported ordinary modality journey.
- Metadata-only access does not expose signal bytes, raw table rows, protected
  previews or hidden dependency names/counts through a new endpoint.

### X04 Exercise current authority and recovery with mixed recordings

Owner: integration/recovery lead. Dependencies: X02, B04, T05, E05, G05.

Deliver mixed-format cases through the existing authority, durable import, view,
publication and recovery machinery. Consume core-hardening implementations rather
than creating a separate modality policy or recovery engine.

Acceptance criteria:

- Interrupted import of a triplet, split family or directory resumes without
  duplicate identity; no incomplete family becomes an accepted release.
- Revocation during long assessment, view creation or delivery is enforced at the
  applicable current-authority boundaries. A dependency link never grants access.
- A denied required dependency prevents a complete scientific export; it cannot
  silently disappear from the bundle or leak through error details.
- The core restore rehearsal preserves exact multimodal historical IDs, member
  bytes and relationship evidence, applies current restrictions, and successfully
  reopens legitimate access. Unknown profile/reader versions are handled explicitly.

## M7 Release qualification

### Q01 Complete differential conformance and interoperability evidence

Owner: scientific QA lead. Dependencies: B04, T05, E05, G05, X02.

Deliver a coverage-to-case ledger and final independent checks on the candidate build.

Acceptance criteria:

- Every claimed format/variant/operation maps to passing positive and negative
  cases. The ledger identifies exactly which BIDS rules remain outside coverage.
- Official validator output is retained with exact schema/configuration; every
  discrepancy is classified as implementation defect, validator limitation or
  documented repository policy. Implementation defects affecting a claimed
  capability block release, and ignore lists cannot suppress entire new modalities.
- External readers open all supported electrophysiology exports. Table oracles
  verify behavioural/physiological transformations, and byte inventories verify
  preservation. The [MNE-BIDS documentation](https://mne.tools/mne-bids/stable/index.html)
  is an integration reference; tool versions are pinned by P03.

### Q02 Qualify multimodal resource use

Owner: performance/backend lead. Dependencies: X04, T01.

Deliver a versioned extension to the existing engineering workload, expected
answers and measured resource/latency evidence on the same declared host envelope.

Acceptance criteria:

- Include long compressed eye-tracking tables, many small vendor-directory files,
  split recordings, shared companions, repeated metadata corrections and mixed
  queries under concurrent import, browsing and slow downloads.
- Meet the inherited and additional numeric budgets below, including cancellation,
  queue overflow, current authorization and exact query/export answers.
- A deliberate input just beyond each limit is refused predictably without false
  completion. Raising the 10,000-entry limit requires separate measured evidence;
  no study is silently split to evade it.

### Q03 Qualify package installation and upgrades

Owner: service/release lead. Dependencies: F06, X04.

Deliver one reproducible service artifact with the declared reader installation
options, operator documentation and old/new package rehearsal.

Acceptance criteria:

- An isolated source build uses the verified bids4s pin and retained fixture/tool
  versions. Optional reader components have dependency/digest/license inventory
  and a supported health/capability check.
- Upgrade a populated old package, then read old science and create new multimodal
  releases. Pending operations and old exports remain correct; unsupported rollback
  is refused and the documented recovery path is exercised.
- The packaged API/console completes a modality smoke journey. Missing optional
  readers produce actionable capability messages while other supported workflows
  continue. No developer checkout is required at runtime.

### Q04 Review and release the combined capability

Owner: release lead. Dependencies: Q01, Q02, Q03.

Deliver the final capability matrix, evidence index, release notes and runnable demos.
The production-readiness claim also consumes the applicable H01–H06 and H00 core
reviews; passing modality tests does not close those reviews automatically.

Acceptance criteria:

- One identified source/build passes ordinary, PostgreSQL, isolated-source,
  scientific corpus, package/upgrade, workload and recovery gates. Receipts name
  source fingerprints, runtime versions, commands, results and remaining limits.
- The documented format/operation matrix matches actual API/UI behavior; all
  initial scope rows pass, or the release explicitly names its narrower scope and
  leaves omitted work open instead of marking the complete roadmap done.
- Four standalone modality demos plus a mixed-study demo are reproducible using
  permitted data. A curator can complete each without a developer repairing files.
- A named scientific reviewer checks the actual exported fixtures and evidence for
  each modality before a scientifically qualified release claim. Engineering
  execution continues with fixtures while that review is scheduled.

## Common acceptance matrix

Apply these cases to every modality gate. A format-specific exception must be
declared in P01 and shown in the capability matrix, not discovered after release.

| Case | Observable result |
| --- | --- |
| Valid import | Complete inventory and exact bytes; deterministic interpretation/checks; ordinary acceptance succeeds. |
| Invalid or unsupported input | Specific bounded findings; no accepted-head change; source remains accounted for under existing staging retention. |
| Inheritance | Root/session/run overrides and ambiguity have expected origins; no cross-participant or cross-datatype leakage. |
| Companions | Required membership is complete; shared dependencies and missing/ambiguous references have expected closure. |
| Correction | Preview names all affected recordings; new evidence binds changed bytes/metadata; accepted history remains reproducible. |
| Rename and removal | Supported plans repair all references atomically; unsupported embedded rewrites and partial required families fail. |
| Discovery | Reference and SQL answers match independently authored expectations; pagination and counts expose only authorized data. |
| Frozen selection | Membership survives later corrections; access is rechecked; historical meaning is not reinterpreted implicitly. |
| Full export | Original relative paths, files, directory structure and file hashes are reproduced exactly. |
| Scientific view | Required dependencies and approved fields are retained; receipts explain every generated output; independent readers can use the result. |
| Re-import | Declared transformed content is assessed successfully with equivalent supported facts and relationships; new destination IDs need not equal source IDs. |
| Interruption | Import, checks and output generation recover/cancel without partial scientific success or duplicate publication. |
| Access changes | Current authority applies to original content, dependent content, previews, output publication and long delivery. |
| Upgrade and restore | Historical identities/bytes/meaning survive, current restrictions remain effective, and authorized use can resume. |

## Resource budgets and evidence retention

Keep the existing engineering workload (`engineering-workload.md` in the working tree) and its versioned resource
envelope as the base. Its targets include 3 GiB service RSS, four runtime database
connections, four active downloads, bounded control concurrency, cancellation
resource release within two seconds, and the existing per-chunk authorization
bound. The full numeric workload remains authoritative; these examples do not
replace its other limits or imply that they have already passed.

Add these proposed multimodal targets in P01, before measuring acceptance:

- Under a fixed schema and fixed bounded diagnostics, streaming one table adds at
  most 256 MiB resident memory above the same service's idle baseline. Increasing
  decoded input from 128 MiB to 1 GiB adds at most 64 MiB further peak memory.
  Record JVM, child-reader and aggregate RSS separately; a worker cannot hide
  unbounded memory outside the service process.
- Quick fixtures cover a ten-minute, 1 kHz eye stream; stress includes a four-hour,
  1 kHz stream plus the explicit decoded-size cases. Expected rows and values are
  generated independently. Freeze column counts, encodings, compression and
  hardware settings in the workload descriptor.
- Use existing query/list latency budgets with mixed facts; table previews are
  bounded pages and do not trigger a full signal decode. Long scientific scans
  run as bounded/cancellable work with progress, not an unbounded HTTP request.
- Enforce the current intake count including directories and a new explicit
  decompressed-size/row/field/check-time limit for each profile. Test each exact
  boundary and one-over-limit case; document refusals in the support matrix.

Keep source manifests, fixture licenses, oracle answers and compact receipts under
version control. Large permitted data and logs may live in a content-addressed
qualification store with reproducible retrieval and hashes. Proposed locations are
`modules/app/src/test/resources/multimodal-conformance/`,
`docs/workloads/multimodal-v1.json` and `docs/evidence/multimodal/`.

Use existing gates (`sbt check`, `scripts/check-postgres.sh`,
`scripts/check-clean-source.sh`) plus new focused suites/harnesses created by the
tasks. Extend validator comparison beside `scripts/check-bids-conformance.py`
without replacing its historical MRI fixtures. Future test names and scripts
must be documented as proposed until they exist and run.

## Decisions and risks to settle early

| Decision or risk | Owner task | Required treatment |
| --- | --- | --- |
| Exact format and acquisition variants | P01 | Freeze initial contracts and use real pilot collections to prioritize additions; unsupported variants remain visible. |
| Behavioural events-only interpretation | P01 | Reconcile normative text, schema and validator behavior before advertising support for that variant. |
| Binary reader cost and packaging | P03 | Measure the four formats early; choose an explicit reader contract and retain independent expectations. |
| Directory recording identity and grouping | F02 | Prove stable membership and partial-edit refusal before adding MEG-specific UI assumptions. |
| New facts and legacy digests | F04–F06 | Version wire/identity contracts and exercise retained old packages before migration. |
| Large physiological tables | T01 | Establish streaming and cancellation before adding previews or column projection. |
| Embedded identifiers and references | F05, E03, G01–G04 | Explain preserved binary metadata; allow only qualified rewrites; preserve readable path layouts. |
| Device clocks and missing synchronization | T02, X01 | Preserve evidence and uncertainty; prohibit inferred alignment from labels alone. |
| Corpus permission or scientific reviewer availability | P02, Q04 | Continue engineering with synthetic cases, but leave affected acquired-data/review qualification open. |
| Concurrent core schema/policy work | F06, X04, Q03 | Coordinate with the existing migration/recovery owners; use their protocols rather than a second implementation. |

## First execution tranche and later expansion

Start by scheduling P01–P03. Retain the existing MRI baseline, select the permitted
behavioural and electrophysiology fixtures, and make the reader decision. Then
implement F01/F03 and the minimum F02 recording description needed for behavioural
data, while completing the persistence, projection and console contracts required
by B04. Deliver behavioural support before building every MEG reader or optimizing
every possible format.

After the initial four deliveries, prioritize additional EEG formats and segmented
recordings from actual collections, followed by additional MEG vendors. iEEG,
fNIRS, PET and other BIDS modalities can reuse the profile, recording and evidence
contracts but each needs its own scope, licensed corpus and complete acceptance
gate. Vendor conversion, visual signal QA and processing/derivative profiles should
be separate backlogs tied to named tools and exact input/output contracts.
