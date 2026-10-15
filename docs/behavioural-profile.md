# Behavioural BIDS profile

The first multimodal implementation adds bounded raw behavioural tables to
Sanctity's existing MRI repository. Service/API imports and the operator import
CLI use `RepositoryBidsInterpreter`, which selects the retained MRI interpreter
for MRI-only content and adds the behavioural profile when `beh/` data is present.

## Supported data

- Subject/session/task/run `_beh.tsv` recordings and their JSON metadata in the
  matching `beh/` directory. A behavioural table can omit onset and duration.
- Optional `_events.tsv` companions, selected by participant/session/task/run
  scope and inheritance directory, with column dictionaries and local stimuli.
- Lexical response values and decimal precision, including negative response
  times and `n/a` missingness. HED text survives preservation and explicit column
  disclosure; HED semantics remain unvalidated.
- Participant, session and scan summaries through the common projection helper.

The registry targets the bounded rules from
[BIDS 1.11.2 behavioural experiments](https://bids-specification.readthedocs.io/en/v1.11.2/modality-specific-files/behavioral-experiments.html).
The initial registry uses alphanumeric labels. The events policy requires an
applicable corresponding behavioural table; an events-only behavioural dataset
is explicitly refused by this profile. That requirement is a bounded repository
coverage choice, not a claim that BIDS requires a neural recording.

Physiology/eye-tracking, EEG and MEG remain subsequent roadmap deliveries. Their
scientific files cannot silently receive this profile's qualified assessment.
The initial wrapper explicitly blocks electrophysiology and other unimplemented
datatype folders; the behavioural placement rules reject unimplemented streams
inside `beh/`.

## Assessment and evidence

`RepositoryBidsContext.Behavioural` is `sanctity-mri-behavioural-v1`.
Behavioural-containing releases use `native-mri-behavioural-v1` interpretation
and `repository-mri-behavioural-admission-v1` admission. The graph extends the
existing MRI vocabulary with `bids.behavioural-events` and
`bids.behavioural-stimuli`. MRI interpretation and checks continue to run on MRI
files in a mixed MRI/behavioural source.

Each behavioural/event check binds the exact table and effective metadata blobs.
Checks report syntax, response-time, dictionary and stimulus-reference findings.
The interpreter records unavailable behavioural timing rather than inventing a
clock or using an image header. Ambiguous companions, orphan events, mismatched
subject/session placement and unsafe/missing local stimuli block admission.

The small-table profile allows 4 MiB per document, 32 MiB total assessed tables,
100,000 rows and 256 columns. Oversized documents are refused without reading
their bytes. Reads verify size and digest, use strict UTF-8, and retain storage
failures as operational failures. Streaming large physiological tables is a
separate roadmap item.

## Curation and scientific views

The existing authenticated corrections endpoint now accepts supported behavioural
and event TSV replacements as well as JSON. The console offers these tables in
the correction selector and on file inspection. A prepared replacement receives
ordinary assessment, review and atomic acceptance; invalid content leaves the
accepted head unchanged.

`BehaviouralViewCompiler` works through the existing `ScientificViews` application
API. Set `DisclosureProfile.behaviouralFields` explicitly for behavioural columns
and `eventFields` for event columns. Legacy disclosure hashes retain their
previous encoding; a behavioural mask uses `sanctity.scientific-view.v3`.
Column dictionaries follow disclosed columns. A retained `stim_file` column
retains its referenced assets; removing that column drops assets no longer needed
by retained tables. Required event timing columns cannot be omitted.
Opaque retained HED annotations preserve their source HED version through explicit
disclosure. Unsupported metadata reference vocabularies cause a view refusal.

Views localize inherited events, generate effective approved metadata, project
summaries and retain a neutral dataset description and source license. Every
output has an exact transformation receipt. Full export retains original bytes;
projected tables preserve their approved lexical values and missingness.

`BidsChangeCompiler(RepositoryBidsContext.Behavioural)` supports behavioural edits
and repairs stimulus references in behavioural tables as well as events. A root
rename/removal must include its applicable local family; a rename that loses
inherited event applicability is refused. Participant/session relabeling remains
outside this edit profile. This application API does not add a new HTTP view or
file-change endpoint.

## Reproduction and qualification

```sh
sbt 'app/testOnly sanctity.app.BehaviouralSuite sanctity.app.BehaviouralResourceSuite'
sbt 'app/runMain sanctity.app.BehaviouralDemo' | tee target/behavioural-demo.log
python3 scripts/check-behavioural.py --demo-log target/behavioural-demo.log --receipt target/behavioural-independent.json
bash scripts/check-postgres.sh
```

After the broad integration pass, a focused rerun can use
`SANCTITY_INTEGRATION_SUITES=sanctity.postgres.BehaviouralPersistenceSuite bash scripts/check-postgres.sh`.
This selects the integration suite while retaining the ordinary checks and complete
existing PostgreSQL crash/restore rehearsal; the default command still runs every
integration suite.

The retained demo uses synthetic CC0 tables and a synthetic stimulus. The independent
standard-library checker verifies full-export bytes/directories, subset membership,
lexical precision, missingness, events, participant values, dictionary disclosure,
description and stimulus bytes. Shared lifecycle tests run on the reference and
PostgreSQL catalogs; resource tests exercise oversized/invalid/corrupt input,
storage failures and cancellation. PostgreSQL also exercises authenticated TSV
correction through the HTTP adapter.

A [pinned acquired response excerpt](../modules/app/src/test/resources/multimodal-conformance/face-behavioural/README.md)
also exercises eight CC0 response rows and their original dictionaries. Its source
stimulus files are empty examples, so it qualifies table/dependency handling and
opaque HED-version preservation rather than image display or a complete study.

The broader [multimodal plan](multimodal-bids-plan.md) remains the qualification
contract. Complete acquired-study qualification, official-validator comparison,
the complete browser view journey, generalized logical-recording/query contracts,
large streaming tables and the other modalities remain open. A local synthetic
pass does not close M2 or the complete multimodal epic.
